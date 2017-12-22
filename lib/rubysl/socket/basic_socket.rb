# Copyright (c) 2013, Brian Shirai
# All rights reserved.
# 
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
# 
# 1. Redistributions of source code must retain the above copyright notice, this
#    list of conditions and the following disclaimer.
# 2. Redistributions in binary form must reproduce the above copyright notice,
#    this list of conditions and the following disclaimer in the documentation
#    and/or other materials provided with the distribution.
# 3. Neither the name of the library nor the names of its contributors may be
#    used to endorse or promote products derived from this software without
#    specific prior written permission.
# 
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY DIRECT,
# INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
# BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
# DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
# OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
# NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
# EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

class BasicSocket < IO
  def self.for_fd(fixnum)
    sock = allocate

    IO.setup(sock, fixnum, nil, true)
    sock.binmode
    # TruffleRuby: start
    sock.do_not_reverse_lookup = do_not_reverse_lookup
    # TruffleRuby: end

    sock
  end

  def self.do_not_reverse_lookup=(setting)
    @no_reverse_lookup = setting
  end

  def self.do_not_reverse_lookup
    @no_reverse_lookup = true unless defined?(@no_reverse_lookup)
    @no_reverse_lookup
  end

  def do_not_reverse_lookup=(setting)
    @no_reverse_lookup = setting
  end

  def do_not_reverse_lookup
    @no_reverse_lookup
  end

  def getsockopt(level, optname)
    sockname = RubySL::Socket::Foreign.getsockname(descriptor)
    family   = RubySL::Socket.family_for_sockaddr_in(sockname)
    level    = RubySL::Socket::SocketOptions.socket_level(level, family)
    optname  = RubySL::Socket::SocketOptions.socket_option(level, optname)
    data     = RubySL::Socket::Foreign.getsockopt(descriptor, level, optname)

    Socket::Option.new(family, level, optname, data)
  end

  def setsockopt(level_or_option, optname = nil, optval = nil)
    if level_or_option and optname and optval
      if level_or_option.is_a?(Socket::Option)
        raise TypeError,
          'expected the first argument to be a Fixnum, Symbol, or String'
      end

      level = level_or_option
    elsif level_or_option.is_a?(Socket::Option)
      raise(ArgumentError, 'given 2, expected 1') if optname

      level   = level_or_option.level
      optname = level_or_option.optname
      optval  = level_or_option.data
    else
      raise TypeError,
        'expected the first argument to be a Fixnum, Symbol, String, or Socket::Option'
    end

    optval = 1 if optval == true
    optval = 0 if optval == false

    sockname = RubySL::Socket::Foreign.getsockname(descriptor)
    family   = RubySL::Socket.family_for_sockaddr_in(sockname)
    level    = RubySL::Socket::SocketOptions.socket_level(level, family)
    optname  = RubySL::Socket::SocketOptions.socket_option(level, optname)
    error    = 0

    if optval.is_a?(Fixnum)
      RubySL::Socket::Foreign.memory_pointer(:socklen_t) do |pointer|
        pointer.write_int(optval)

        error = RubySL::Socket::Foreign
          .setsockopt(descriptor, level, optname, pointer, pointer.total)
      end
    elsif optval.is_a?(String)
      RubySL::Socket::Foreign.memory_pointer(optval.bytesize) do |pointer|
        pointer.write_string(optval)

        error = RubySL::Socket::Foreign
          .setsockopt(descriptor, level, optname, pointer, optval.bytesize)
      end
    else
      raise TypeError, 'socket option should be a Fixnum, String, true, or false'
    end

    Errno.handle('unable to set socket option') if error < 0

    0
  end

  def getsockname
    RubySL::Socket::Foreign.getsockname(descriptor)
  end

  def getpeername
    RubySL::Socket::Foreign.getpeername(descriptor)
  end

  def send(message, flags, dest_sockaddr = nil)
    bytes      = message.bytesize
    bytes_sent = 0

    if dest_sockaddr.is_a?(Addrinfo)
      dest_sockaddr = dest_sockaddr.to_sockaddr
    end

    RubySL::Socket::Foreign.char_pointer(bytes) do |buffer|
      buffer.write_string(message)

      if dest_sockaddr.is_a?(String)
        addr = RubySL::Socket.sockaddr_class_for_socket(self)
          .with_sockaddr(dest_sockaddr)

        begin
          bytes_sent = RubySL::Socket::Foreign
            .sendto(descriptor, buffer, bytes, flags, addr, addr.size)
        ensure
          addr.free
        end
      else
        bytes_sent = RubySL::Socket::Foreign
          .send(descriptor, buffer, bytes, flags)
      end
    end

    RubySL::Socket::Error.write_error('send(2)', self) if bytes_sent < 0

    bytes_sent
  end

  def recv(bytes_to_read, flags = 0)
    RubySL::Socket::Foreign.memory_pointer(bytes_to_read) do |buf|
      n_bytes = RubySL::Socket::Foreign.recv(@descriptor, buf, bytes_to_read, flags)
      Errno.handle('recv(2)') if n_bytes == -1
      return buf.read_string(n_bytes)
    end
  end

  def recvmsg(max_msg_len = nil, flags = 0, *_)
    socket_type = getsockopt(:SOCKET, :TYPE).int

    if socket_type == Socket::SOCK_STREAM
      grow_msg = false
    else
      grow_msg = max_msg_len.nil?
    end

    flags |= Socket::MSG_PEEK if grow_msg

    msg_len = max_msg_len || 4096

    loop do
      msg_buffer = RubySL::Socket::Foreign.char_pointer(msg_len)
      address    = RubySL::Socket.sockaddr_class_for_socket(self).new
      io_vec     = RubySL::Socket::Foreign::Iovec.with_buffer(msg_buffer)
      header     = RubySL::Socket::Foreign::Msghdr.with_buffers(address, io_vec)

      begin
        need_more = false

        msg_size = RubySL::Socket::Foreign
          .recvmsg(descriptor, header.pointer, flags)

        RubySL::Socket::Error.read_error('recvmsg(2)', self) if msg_size < 0

        if grow_msg and header.message_truncated?
          need_more = true
          msg_len *= 2
        end

        next if need_more

        # When a socket is actually connected the address structure is not used.
        if header.address_size > 0
          addr = Addrinfo.new(address.to_s, address.family, socket_type)
        else
          addr = Addrinfo.new([Socket::AF_UNSPEC], nil, socket_type)
        end

        return msg_buffer.read_string(msg_size), addr, header.flags
      ensure
        msg_buffer.free
        address.free
        io_vec.free
        header.free
      end
    end

    nil
  end

  def recvmsg_nonblock(max_msg_len = nil, flags = 0, *_)
    fcntl(Fcntl::F_SETFL, Fcntl::O_NONBLOCK)

    recvmsg(max_msg_len, flags | Socket::MSG_DONTWAIT)
  end

  def sendmsg(message, flags = 0, dest_sockaddr = nil, *_)
    msg_buffer = RubySL::Socket::Foreign.char_pointer(message.bytesize)
    io_vec     = RubySL::Socket::Foreign::Iovec.with_buffer(msg_buffer)
    header     = RubySL::Socket::Foreign::Msghdr.new
    address    = nil

    begin
      msg_buffer.write_string(message)

      header.message = io_vec

      if dest_sockaddr.is_a?(Addrinfo)
        dest_sockaddr = dest_sockaddr.to_sockaddr
      end

      if dest_sockaddr.is_a?(String)
        address = RubySL::Socket::Foreign::SockaddrIn
          .with_sockaddr(dest_sockaddr)

        header.address = address
      end

      num_bytes = RubySL::Socket::Foreign
        .sendmsg(descriptor, header.pointer, flags)

      RubySL::Socket::Error.read_error('sendmsg(2)', self) if num_bytes < 0

      num_bytes
    ensure
      address.free if address
      header.free
      io_vec.free
      msg_buffer.free
    end
  end

  def sendmsg_nonblock(message, flags = 0, dest_sockaddr = nil, *_)
    fcntl(Fcntl::F_SETFL, Fcntl::O_NONBLOCK)

    sendmsg(message, flags | Socket::MSG_DONTWAIT, dest_sockaddr)
  end

  def close_read
    ensure_open

    if mode_read_only?
      return close
    end

    RubySL::Socket::Foreign.shutdown(descriptor, 0)

    force_write_only

    nil
  end

  def close_write
    ensure_open

    if mode_write_only?
      return close
    end

    RubySL::Socket::Foreign.shutdown(descriptor, 1)

    force_read_only

    nil
  end

  def recv_nonblock(bytes_to_read, flags = 0)
    fcntl(Fcntl::F_SETFL, Fcntl::O_NONBLOCK)

    RubySL::Socket::Error.wrap_read_nonblock do
      recv(bytes_to_read, flags)
    end
  end

  def shutdown(how = Socket::SHUT_RDWR)
    how = RubySL::Socket.shutdown_option(how)
    err = RubySL::Socket::Foreign.shutdown(descriptor, how)

    Errno.handle('shutdown(2)') unless err == 0

    0
  end

  # MRI defines this method in BasicSocket and stuffs all logic in it. Since
  # inheriting classes behave differently we overwrite this method in said
  # classes. The method here exists so that code such as the following still
  # works: BasicSocket.method_defined?(:local_address).
  def local_address
    raise NotImplementedError,
      'This method must be implemented by classes inheriting from BasicSocket'
  end

  def remote_address
    raise NotImplementedError,
      'This method must be implemented by classes inheriting from BasicSocket'
  end

  def getpeereid
    RubySL::Socket::Foreign.getpeereid(descriptor)
  end
end

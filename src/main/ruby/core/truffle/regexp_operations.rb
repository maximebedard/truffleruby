# Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 1.0
# GNU General Public License version 2
# GNU Lesser General Public License version 2.1

module Truffle
  module RegexpOperations
    def self.match(re, str, pos=0)
      return nil unless str

      str = str.to_s if str.is_a?(Symbol)
      str = StringValue(str)

      m = Truffle::Mirror.reflect str
      pos = pos < 0 ? pos + str.size : pos
      pos = m.character_to_byte_index pos
      re.search_region(str, pos, str.bytesize, true)
    end

    def self.last_match(a_binding)
      Truffle::KernelOperations.frame_local_variable_get(:$~, a_binding)
    end
    Truffle::Graal.always_split(method(:last_match))

    def self.set_last_match(value, a_binding)
      unless value.nil? || Truffle::Type.object_kind_of?(value, MatchData)
        raise TypeError, "Wrong argument type #{value} (expected MatchData)"
      end
      Truffle::KernelOperations.frame_local_variable_set(:$~, a_binding, value) if a_binding
    end
    Truffle::Graal.always_split(method(:set_last_match))
  end
end

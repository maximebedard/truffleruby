/*
 * Copyright (c) 2014, 2018 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.interop;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Log;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.SnippetNode;
import org.truffleruby.language.control.JavaException;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.language.dispatch.DispatchNode;
import org.truffleruby.language.methods.ExceptionTranslatingNode;
import org.truffleruby.language.methods.UnsupportedOperationBehavior;

import java.util.Arrays;

@NodeChildren({
        @NodeChild("receiver"),
        @NodeChild("args")
})
public abstract class OutgoingForeignCallNode extends RubyNode {

    @Child private ExceptionTranslatingNode exceptionTranslatingNode;

    private final String name;
    private final BranchProfile errorProfile = BranchProfile.create();

    public OutgoingForeignCallNode(String name) {
        this.name = name;
        this.exceptionTranslatingNode = new ExceptionTranslatingNode(null, UnsupportedOperationBehavior.TYPE_ERROR);
    }

    public abstract Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args);

    @Specialization(
            guards = "args.length == cachedArgsLength",
            limit = "getCacheLimit()"
    )
    public Object callCached(
            VirtualFrame frame,
            TruffleObject receiver,
            Object[] args,
            @Cached("args.length") int cachedArgsLength,
            @Cached("createHelperNode(cachedArgsLength)") OutgoingNode outgoingNode) {
        return doCall(frame, receiver, outgoingNode, args);
    }

    @Specialization(replaces = "callCached")
    public Object callUncached(
            VirtualFrame frame,
            TruffleObject receiver,
            Object[] args) {
        Log.notOptimizedOnce("megamorphic outgoing foreign call");

        final OutgoingNode outgoingNode = createHelperNode(args.length);
        return doCall(frame, receiver, outgoingNode, args);
    }

    private Object doCall(VirtualFrame frame, TruffleObject receiver, OutgoingNode outgoingNode, Object[] args) {
        try {
            return outgoingNode.executeCall(frame, receiver, args);
        } catch (Throwable t) {
            errorProfile.enter();
            throw exceptionTranslatingNode.translate(t);
        }
    }

    @TruffleBoundary
    protected OutgoingNode createHelperNode(int argsLength) {
        if (name.equals("[]") && argsLength == 1) {
            return new IndexReadOutgoingNode();
        } else if (name.equals("[]=") && argsLength == 2) {
            return new IndexWriteOutgoingNode();
        } else if (name.length() >= 2 && name.charAt(name.length() - 2) != '=' && name.charAt(name.length() - 1) == '=' && argsLength == 1) {
            return new PropertyWriteOutgoingNode(name.substring(0, name.length() - 1));
        } else if (name.equals("call")) {
            return new CallOutgoingNode(argsLength);
        } else if (name.equals("new")) {
            return new NewOutgoingNode(argsLength);
        } else if (name.equals("to_a") || name.equals("to_ary")) {
            return new ToAOutgoingNode();
        } else if (name.equals("respond_to?")) {
            return new RespondToOutgoingNode();
        } else if (name.equals("inspect")) {
            return new InspectOutgoingNode();
        } else if (name.equals("__send__")) {
            return new SendOutgoingNode();
        } else if (name.equals("nil?") && argsLength == 0) {
            return new IsNilOutgoingNode();
        } else if (name.equals("equal?") && argsLength == 1) {
            return new IsReferenceEqualOutgoingNode();
        } else if (name.endsWith("!") && argsLength == 0) {
            return new InvokeOutgoingNode(name.substring(0, name.length() - 1), argsLength);
        } else if (argsLength == 0) {
            return new PropertyReadOutgoingNode(name);
        } else {
            return new InvokeOutgoingNode(name, argsLength);
        }
    }

    protected RubyToForeignNode[] createToForeignNodes(int argsLength) {
        final RubyToForeignNode[] toForeignNodes = new RubyToForeignNode[argsLength];

        for (int n = 0; n < argsLength; n++) {
            toForeignNodes[n] = RubyToForeignNodeGen.create(null);
        }

        return toForeignNodes;
    }

    @ExplodeLoop
    protected Object[] argsToForeign(VirtualFrame frame, RubyToForeignNode[] toForeignNodes, Object[] args) {
        assert toForeignNodes.length == args.length;

        final Object[] foreignArgs = new Object[toForeignNodes.length];

        for (int n = 0; n < toForeignNodes.length; n++) {
            foreignArgs[n] = toForeignNodes[n].executeConvert(args[n]);
        }

        return foreignArgs;
    }

    protected int getCacheLimit() {
        return getContext().getOptions().INTEROP_EXECUTE_CACHE;
    }

    protected abstract static class OutgoingNode extends Node {

        private final BranchProfile exceptionProfile = BranchProfile.create();
        private final BranchProfile unknownIdentifierProfile = BranchProfile.create();

        public abstract Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args);

        protected void exceptionProfile() {
            exceptionProfile.enter();
        }

        protected void unknownIdentifierProfile() {
            unknownIdentifierProfile.enter();
        }

    }

    protected class IndexReadOutgoingNode extends OutgoingNode {

        @Child private Node node;
        @Child private RubyToForeignNode rubyToForeignNode = RubyToForeignNode.create();
        @Child private ForeignToRubyNode foreignToRubyNode = ForeignToRubyNode.create();

        public IndexReadOutgoingNode() {
            node = Message.READ.createNode();
        }

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            assert args.length == 1;

            final Object name = rubyToForeignNode.executeConvert(args[0]);
            final Object foreign;
            try {
                foreign = ForeignAccess.sendRead(node, receiver, name);
            } catch (UnknownIdentifierException e) {
                unknownIdentifierProfile();
                throw new RaiseException(coreExceptions().nameErrorUnknownIdentifier(receiver, name, e, this));
            } catch (UnsupportedMessageException e) {
                exceptionProfile();
                throw new JavaException(e);
            }

            return foreignToRubyNode.executeConvert(foreign);
        }

    }

    protected class IndexWriteOutgoingNode extends OutgoingNode {

        @Child private Node node;
        @Child private RubyToForeignNode identifierToForeignNode = RubyToForeignNode.create();
        @Child private RubyToForeignNode valueToForeignNode = RubyToForeignNode.create();
        @Child private ForeignToRubyNode foreignToRubyNode = ForeignToRubyNode.create();

        public IndexWriteOutgoingNode() {
            node = Message.WRITE.createNode();
        }

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            assert args.length == 2;

            final Object foreign;

            try {
                foreign = ForeignAccess.sendWrite(
                        node,
                        receiver,
                        identifierToForeignNode.executeConvert(args[0]),
                        valueToForeignNode.executeConvert(args[1]));
            } catch (UnknownIdentifierException e) {
                unknownIdentifierProfile();
                throw new RaiseException(coreExceptions().nameErrorUnknownIdentifier(receiver, name, e, this));
            } catch (UnsupportedMessageException | UnsupportedTypeException e) {
                exceptionProfile();
                throw new JavaException(e);
            }

            return foreignToRubyNode.executeConvert(foreign);
        }

    }

    protected class PropertyReadOutgoingNode extends OutgoingNode {

        private final String name;

        @Child private Node node;
        @Child private ForeignToRubyNode foreignToRubyNode = ForeignToRubyNode.create();

        public PropertyReadOutgoingNode(String name) {
            this.name = name;
            node = Message.READ.createNode();
        }

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            assert args.length == 0;

            final Object foreign;

            try {
                foreign = ForeignAccess.sendRead(node, receiver, name);
            } catch (UnknownIdentifierException e) {
                unknownIdentifierProfile();
                throw new RaiseException(coreExceptions().nameErrorUnknownIdentifier(receiver, name, e, this));
            } catch (UnsupportedMessageException e) {
                exceptionProfile();
                throw new JavaException(e);
            }

            return foreignToRubyNode.executeConvert(foreign);
        }

    }

    protected class PropertyWriteOutgoingNode extends OutgoingNode {

        private final String name;

        @Child private Node node;
        @Child private RubyToForeignNode rubyToForeignNode = RubyToForeignNode.create();
        @Child private ForeignToRubyNode foreignToRubyNode = ForeignToRubyNode.create();

        public PropertyWriteOutgoingNode(String name) {
            this.name = name;
            node = Message.WRITE.createNode();
        }

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            assert args.length == 1;

            final Object foreign;

            try {
                foreign = ForeignAccess.sendWrite(
                        node,
                        receiver,
                        name,
                        rubyToForeignNode.executeConvert(args[0]));
            } catch (UnknownIdentifierException e) {
                unknownIdentifierProfile();
                throw new RaiseException(coreExceptions().nameErrorUnknownIdentifier(receiver, name, e, this));
            } catch (UnsupportedMessageException | UnsupportedTypeException e) {
                exceptionProfile();
                throw new JavaException(e);
            }

            return foreignToRubyNode.executeConvert(foreign);
        }

    }

    protected class CallOutgoingNode extends OutgoingNode {

        private final int argsLength;

        @Child private Node node;
        @Child private ForeignToRubyNode foreignToRubyNode = ForeignToRubyNode.create();
        @Child private RubyToForeignArgumentsNode rubyToForeignArgumentsNode = RubyToForeignArgumentsNode.create();

        public CallOutgoingNode(int argsLength) {
            this.argsLength = argsLength;
            node = Message.createExecute(argsLength).createNode();
        }

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            assert args.length == argsLength;

            final Object foreign;

            try {
                foreign = ForeignAccess.sendExecute(
                        node,
                        receiver,
                        rubyToForeignArgumentsNode.executeConvert(args));
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                exceptionProfile();
                throw new JavaException(e);
            }

            return foreignToRubyNode.executeConvert(foreign);
        }

    }

    protected class SendOutgoingNode extends OutgoingNode {

        @Child private CallDispatchHeadNode dispatchNode = CallDispatchHeadNode.createReturnMissing();

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            if (args.length < 1) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreExceptions().argumentError(args.length, 1, this));
            }

            final Object name = args[0];
            final Object[] sendArgs = Arrays.copyOfRange(args, 1, args.length);

            final Object result = dispatchNode.call(frame, receiver, name, sendArgs);

            assert result != DispatchNode.MISSING;

            return result;
        }

    }

    protected class NewOutgoingNode extends OutgoingNode {

        private final int argsLength;

        @Child private Node node;
        @Child private ForeignToRubyNode foreignToRubyNode = ForeignToRubyNode.create();
        @Child private RubyToForeignArgumentsNode rubyToForeignArgumentsNode = RubyToForeignArgumentsNode.create();

        public NewOutgoingNode(int argsLength) {
            this.argsLength = argsLength;
            node = Message.createNew(argsLength).createNode();
        }

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            assert args.length == argsLength;

            final Object foreign;

            try {
                foreign = ForeignAccess.sendNew(
                        node,
                        receiver,
                        rubyToForeignArgumentsNode.executeConvert(args));
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                exceptionProfile();
                throw new JavaException(e);
            }

            return foreignToRubyNode.executeConvert(foreign);
        }

    }

    protected class ToAOutgoingNode extends OutgoingNode {

        @Child private SnippetNode snippetNode = new SnippetNode();

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            if (args.length > 0) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreExceptions().argumentError(args.length, 0, this));
            }

            return snippetNode.execute(frame, "Truffle::Interop.to_array(object)", "object", receiver);
        }

    }

    protected class RespondToOutgoingNode extends OutgoingNode {

        @Child private CallDispatchHeadNode callRespondTo = CallDispatchHeadNode.create();

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            if (args.length != 1) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreExceptions().argumentError(args.length, 1, this));
            }

            return callRespondTo.call(frame, coreLibrary().getTruffleInteropModule(), "respond_to?", receiver, args[0]);
        }

    }

    protected class InspectOutgoingNode extends OutgoingNode {

        @Child private StringNodes.MakeStringNode makeStringNode = StringNodes.MakeStringNode.create();

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            return makeStringNode.executeMake(inspect(receiver), UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }

        @TruffleBoundary
        private String inspect(TruffleObject receiver) {
            return String.format("#<Truffle::Interop::Foreign@%x>", System.identityHashCode(receiver));
        }

    }

    protected class IsNilOutgoingNode extends OutgoingNode {

        @Child private Node node;

        public IsNilOutgoingNode() {
            node = Message.IS_NULL.createNode();
        }

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            assert args.length == 0;

            return ForeignAccess.sendIsNull(node, receiver);
        }

    }

    protected class IsReferenceEqualOutgoingNode extends OutgoingNode {

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            assert args.length == 1;
            return receiver == args[0];
        }

    }

    protected class InvokeOutgoingNode extends OutgoingNode {

        private final String name;
        private final int argsLength;

        @Child private Node node;
        @Child private RubyToForeignArgumentsNode rubyToForeignArgumentsNode = RubyToForeignArgumentsNode.create();
        @Child private ForeignToRubyNode foreignToRubyNode = ForeignToRubyNode.create();

        public InvokeOutgoingNode(String name, int argsLength) {
            this.name = name;
            this.argsLength = argsLength;
            node = Message.createInvoke(argsLength).createNode();
        }

        @Override
        public Object executeCall(VirtualFrame frame, TruffleObject receiver, Object[] args) {
            assert args.length == argsLength;
            final Object[] arguments = rubyToForeignArgumentsNode.executeConvert(args);

            final Object foreign;
            try {
                foreign = ForeignAccess.sendInvoke(
                        node,
                        receiver,
                        name,
                        arguments);
            } catch (UnknownIdentifierException e) {
                unknownIdentifierProfile();
                throw new RaiseException(coreExceptions().noMethodErrorUnknownIdentifier(receiver, name, args, e, this));
            } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                exceptionProfile();
                throw new JavaException(e);
            }

            return foreignToRubyNode.executeConvert(foreign);
        }

    }


}

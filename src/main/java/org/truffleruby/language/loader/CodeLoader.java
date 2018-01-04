/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.language.loader;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.Source;
import org.jcodings.Encoding;
import org.truffleruby.RubyContext;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.RubyRootNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.parser.ParserContext;
import org.truffleruby.parser.TranslatorDriver;

public class CodeLoader {

    private final RubyContext context;

    public CodeLoader(RubyContext context) {
        this.context = context;
    }

    @TruffleBoundary
    public RubyRootNode parse(Source source,
                              Encoding defaultEncoding,
                              ParserContext parserContext,
                              FrameDescriptor frameDescriptor,
                              MaterializedFrame parentFrame,
                              boolean ownScopeForAssignments,
                              Node currentNode) {
        final TranslatorDriver translator = new TranslatorDriver(context);
        return translator.parse(source, defaultEncoding, parserContext, null, frameDescriptor, parentFrame,
                ownScopeForAssignments, currentNode);
    }

    @TruffleBoundary
    public RubyRootNode parse(Source source,
                              Encoding defaultEncoding,
                              ParserContext parserContext,
                              MaterializedFrame parentFrame,
                              boolean ownScopeForAssignments,
                              Node currentNode) {
        return parse(source, defaultEncoding, parserContext, null, parentFrame, ownScopeForAssignments, currentNode);
    }

    @TruffleBoundary
    public DeferredCall prepareExecute(ParserContext parserContext,
                          DeclarationContext declarationContext,
                          RubyRootNode rootNode,
                          MaterializedFrame parentFrame,
                          Object self) {
        final CallTarget callTarget = Truffle.getRuntime().createCallTarget(rootNode);

        final DynamicObject declaringModule;

        if (parserContext == ParserContext.EVAL && parentFrame != null) {
            declaringModule = RubyArguments.getMethod(parentFrame).getDeclaringModule();
        } else if (parserContext == ParserContext.MODULE) {
            declaringModule = (DynamicObject) self;
        } else {
            declaringModule = context.getCoreLibrary().getObjectClass();
        }

        final LexicalScope lexicalScope;
        if (parentFrame != null) {
            lexicalScope = RubyArguments.getMethod(parentFrame).getLexicalScope();
        } else {
            lexicalScope = context.getRootLexicalScope();
        }

        final InternalMethod method = new InternalMethod(
                context,
                rootNode.getSharedMethodInfo(),
                lexicalScope,
                DeclarationContext.MODULE,
                rootNode.getSharedMethodInfo().getName(),
                declaringModule,
                Visibility.PUBLIC,
                false,
                callTarget);

        return new DeferredCall(callTarget, RubyArguments.pack(
                parentFrame,
                null,
                method,
                declarationContext,
                null,
                self,
                null,
                RubyNode.EMPTY_ARGUMENTS));
    }

    public static class DeferredCall {

        private final CallTarget callTarget;
        private final Object[] arguments;

        public DeferredCall(CallTarget callTarget, Object[] arguments) {
            this.callTarget = callTarget;
            this.arguments = arguments;
        }

        public Object call(IndirectCallNode callNode) {
            return callNode.call(callTarget, arguments);
        }

        public Object callWithoutCallNode() {
            return callTarget.call(arguments);
        }

    }

}

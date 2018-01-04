/*
 * Copyright (c) 2014, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.language.dispatch;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.RubyContext;
import org.truffleruby.core.module.MethodLookupResult;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.methods.InternalMethod;

public class CachedBoxedSymbolDispatchNode extends CachedDispatchNode {

    @CompilationFinal(dimensions = 1) private final Assumption[] assumptions;

    private final InternalMethod method;
    @Child private DirectCallNode callNode;

    public CachedBoxedSymbolDispatchNode(
            RubyContext context,
            Object cachedName,
            DispatchNode next,
            MethodLookupResult methodLookup,
            DispatchAction dispatchAction) {
        super(context, cachedName, next, dispatchAction);

        this.assumptions = methodLookup.getAssumptions();
        this.method = methodLookup.getMethod();
        this.callNode = Truffle.getRuntime().createDirectCallNode(method.getCallTarget());
    }

    @Override
    protected void reassessSplittingInliningStrategy() {
        applySplittingInliningStrategy(callNode, method);
    }

    @Override
    protected boolean guard(Object methodName, Object receiver) {
        return guardName(methodName) && (RubyGuards.isRubySymbol(receiver));
    }

    @Override
    public Object executeDispatch(
            VirtualFrame frame,
            Object receiverObject,
            Object methodName,
            DynamicObject blockObject,
            DeclarationContext declarationContext,
            Object[] argumentsObjects) {
        try {
            checkAssumptions(assumptions);
        } catch (InvalidAssumptionException e) {
            return resetAndDispatch(
                    frame,
                    receiverObject,
                    methodName,
                    blockObject,
                    declarationContext,
                    argumentsObjects,
                    "class modified");
        }

        if (!guard(methodName, receiverObject)) {
            return next.executeDispatch(
                    frame,
                    receiverObject,
                    methodName,
                    blockObject,
                    declarationContext,
                    argumentsObjects);
        }

        switch (getDispatchAction()) {
            case CALL_METHOD:
                return call(callNode, frame, method, receiverObject, blockObject, argumentsObjects);

            case RESPOND_TO_METHOD:
                return true;

            default:
                throw new UnsupportedOperationException();
        }
    }

}

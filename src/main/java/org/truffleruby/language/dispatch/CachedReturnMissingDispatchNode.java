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

import org.truffleruby.RubyContext;
import org.truffleruby.core.module.MethodLookupResult;
import org.truffleruby.language.objects.MetaClassNode;
import org.truffleruby.language.objects.MetaClassNodeGen;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.object.DynamicObject;

public class CachedReturnMissingDispatchNode extends CachedDispatchNode {

    private final DynamicObject expectedClass;
    @CompilationFinal(dimensions = 1) private final Assumption[] assumptions;

    @Child private MetaClassNode metaClassNode;

    public CachedReturnMissingDispatchNode(
            RubyContext context,
            Object cachedName,
            DispatchNode next,
            MethodLookupResult methodLookup,
            DynamicObject expectedClass,
            DispatchAction dispatchAction) {
        super(context, cachedName, next, dispatchAction);

        this.expectedClass = expectedClass;
        this.assumptions = methodLookup.getAssumptions();
        this.metaClassNode = MetaClassNodeGen.create(null);
    }

    @Override
    protected void reassessSplittingInliningStrategy() {
        // Do nothing, this node does not split or inline.
    }

    @Override
    protected boolean guard(Object methodName, Object receiver) {
        return guardName(methodName) &&
                metaClassNode.executeMetaClass(receiver) == expectedClass;
    }

    @Override
    public Object executeDispatch(
            VirtualFrame frame,
            Object receiverObject,
            Object methodName,
            DynamicObject blockObject,
            Object[] argumentsObjects) {
        try {
            checkAssumptions(assumptions);
        } catch (InvalidAssumptionException e) {
            return resetAndDispatch(
                    frame,
                    receiverObject,
                    methodName,
                    blockObject,
                    argumentsObjects,
                    "class modified");
        }

        if (!guard(methodName, receiverObject)) {
            return next.executeDispatch(
                    frame,
                    receiverObject,
                    methodName,
                    blockObject,
                    argumentsObjects);
        }

        switch (getDispatchAction()) {
            case CALL_METHOD:
                return MISSING;

            case RESPOND_TO_METHOD:
                return false;

            default:
                throw new UnsupportedOperationException();
        }
    }

}

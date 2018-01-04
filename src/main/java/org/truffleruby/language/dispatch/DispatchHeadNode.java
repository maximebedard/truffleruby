/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.language.dispatch;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.language.RubyBaseNode;

public abstract class DispatchHeadNode extends RubyBaseNode {

    protected final boolean ignoreVisibility;
    protected final boolean onlyCallPublic;
    protected final MissingBehavior missingBehavior;
    protected final DispatchAction dispatchAction;

    @Child private DispatchNode first;

    protected DispatchHeadNode(
            boolean ignoreVisibility,
            boolean onlyCallPublic,
            MissingBehavior missingBehavior, DispatchAction dispatchAction) {
        this.ignoreVisibility = ignoreVisibility;
        this.onlyCallPublic = onlyCallPublic;
        this.missingBehavior = missingBehavior;
        this.dispatchAction = dispatchAction;
        first = new UnresolvedDispatchNode(ignoreVisibility, onlyCallPublic, missingBehavior, dispatchAction);
    }

    public Object dispatch(
            VirtualFrame frame,
            Object receiverObject,
            Object methodName,
            DynamicObject blockObject,
            Object[] argumentsObjects) {
        return first.executeDispatch(
                frame,
                receiverObject,
                methodName,
                blockObject,
                argumentsObjects);
    }

    public void reset(String reason) {
        first.replace(new UnresolvedDispatchNode(ignoreVisibility, onlyCallPublic, missingBehavior, dispatchAction), reason);
    }

    public DispatchNode getFirstDispatchNode() {
        return first;
    }

    public DispatchAction getDispatchAction() {
        return dispatchAction;
    }

}

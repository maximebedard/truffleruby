/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.exception;

import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectFactory;
import com.oracle.truffle.api.object.dsl.Layout;
import com.oracle.truffle.api.object.dsl.Nullable;
import org.truffleruby.core.basicobject.BasicObjectLayout;
import org.truffleruby.language.backtrace.Backtrace;

@Layout
public interface ExceptionLayout extends BasicObjectLayout {

    DynamicObjectFactory createExceptionShape(
            DynamicObject logicalClass,
            DynamicObject metaClass);

    DynamicObject createException(
            DynamicObjectFactory factory,
            @Nullable Object message,
            @Nullable DynamicObject formatter,
            @Nullable Backtrace backtrace);

    boolean isException(DynamicObject object);

    Object getMessage(DynamicObject object);
    void setMessage(DynamicObject object, Object value);

    DynamicObject getFormatter(DynamicObject object);
    void setFormatter(DynamicObject object, DynamicObject value);

    Backtrace getBacktrace(DynamicObject object);
    void setBacktrace(DynamicObject object, Backtrace value);

}

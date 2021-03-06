/*
 * Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.interop;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.Layouts;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.string.StringCachingGuards;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.RubyNode;

@ImportStatic({ StringCachingGuards.class, StringOperations.class })
@NodeChild(value = "value", type = RubyNode.class)
public abstract class ToJavaStringNode extends RubyNode {

    public static ToJavaStringNode create() {
        return ToJavaStringNodeGen.create(null);
    }

    public abstract String executeToJavaString(Object name);

    @Specialization(guards = { "isRubyString(value)", "equalsNode.execute(rope(value), cachedRope)" }, limit = "getLimit()")
    String stringCached(DynamicObject value,
            @Cached("privatizeRope(value)") Rope cachedRope,
            @Cached("getString(value)") String convertedString,
            @Cached("create()") RopeNodes.EqualNode equalsNode) {
        return convertedString;
    }

    @Specialization(guards = "isRubyString(value)", replaces = "stringCached")
    public String stringUncached(DynamicObject value) {
        return StringOperations.getString(value);
    }

    @Specialization(guards = { "symbol == cachedSymbol", "isRubySymbol(cachedSymbol)" }, limit = "getLimit()")
    public String symbolCached(DynamicObject symbol,
            @Cached("symbol") DynamicObject cachedSymbol,
            @Cached("symbolToString(symbol)") String convertedString) {
        return convertedString;
    }

    @Specialization(guards = "isRubySymbol(symbol)", replaces = "symbolCached")
    public String symbolUncached(DynamicObject symbol) {
        return symbolToString(symbol);
    }

    protected String symbolToString(DynamicObject symbol) {
        return Layouts.SYMBOL.getString(symbol);
    }

    @Specialization(guards = "string == cachedString", limit = "getLimit()")
    public String javaStringCached(String string,
            @Cached("string") String cachedString) {
        return cachedString;
    }

    @Specialization(replaces = "javaStringCached")
    public String javaStringUncached(String value) {
        return value;
    }

    protected int getLimit() {
        return getContext().getOptions().INTEROP_CONVERT_CACHE;
    }

}

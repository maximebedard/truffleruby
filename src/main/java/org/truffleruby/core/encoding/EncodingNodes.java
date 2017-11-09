/*
 * Copyright (c) 2014, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 *
 * Contains code modified from JRuby's RubyConverter.java
 */
package org.truffleruby.core.encoding;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import org.jcodings.Encoding;
import org.jcodings.EncodingDB;
import org.jcodings.EncodingDB.Entry;
import org.jcodings.specific.USASCIIEncoding;
import org.truffleruby.Layouts;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.cast.ToEncodingNode;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.SnippetNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.control.RaiseException;

@CoreClass("Encoding")
public abstract class EncodingNodes {

    @CoreMethod(names = "ascii_compatible?")
    public abstract static class AsciiCompatibleNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "encoding == cachedEncoding", limit = "getCacheLimit()")
        public boolean isAsciiCompatibleCached(DynamicObject encoding,
                                          @Cached("encoding") DynamicObject cachedEncoding,
                                          @Cached("isAsciiCompatible(cachedEncoding)") boolean isAsciiCompatible) {
            return isAsciiCompatible;
        }

        @Specialization(replaces = "isAsciiCompatibleCached")
        public boolean isAsciiCompatibleUncached(DynamicObject encoding) {
            return isAsciiCompatible(encoding);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ENCODING_LOADED_CLASSES_CACHE;
        }

        protected static boolean isAsciiCompatible(DynamicObject encoding) {
            assert RubyGuards.isRubyEncoding(encoding);

            return EncodingOperations.getEncoding(encoding).isAsciiCompatible();
        }
    }

    @NodeChild("encoding")
    public abstract static class GetRubyEncodingNode extends RubyNode {

        public static GetRubyEncodingNode create() {
            return EncodingNodesFactory.GetRubyEncodingNodeGen.create(null);
        }

        public abstract DynamicObject executeGetRubyEncoding(Encoding encoding);

        @Specialization(guards = "isSameEncoding(encoding, cachedRubyEncoding)", limit = "getCacheLimit()")
        protected DynamicObject getRubyEncodingCached(Encoding encoding,
                                                      @Cached("getRubyEncodingUncached(encoding)") DynamicObject cachedRubyEncoding) {
            return cachedRubyEncoding;
        }

        @Specialization(replaces = "getRubyEncodingCached")
        protected DynamicObject getRubyEncodingUncached(Encoding encoding) {
            if (encoding == null) {
                throw new UnsupportedOperationException("cannot convert null Java encoding to a Ruby encoding");
            }

            return getContext().getEncodingManager().getRubyEncoding(encoding);
        }

        protected boolean isSameEncoding(Encoding encoding, DynamicObject rubyEncoding) {
            return encoding == Layouts.ENCODING.getEncoding(rubyEncoding);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ENCODING_LOADED_CLASSES_CACHE;
        }

    }

    @NodeChildren({ @NodeChild("first"), @NodeChild("second") })
    public static abstract class NegotiateCompatibleRopeEncodingNode extends RubyNode {

        public abstract Encoding executeNegotiate(Rope first, Rope second);

        public static NegotiateCompatibleRopeEncodingNode create() {
            return EncodingNodesFactory.NegotiateCompatibleRopeEncodingNodeGen.create(null, null);
        }

        @Specialization(guards = "getEncoding(first) == getEncoding(second)")
        protected Encoding negotiateSameEncodingUncached(Rope first, Rope second) {
            return getEncoding(first);
        }

        @Specialization(guards = {
                "firstEncoding != secondEncoding",
                "isEmpty(first) == isFirstEmpty",
                "isEmpty(second) == isSecondEmpty",
                "getCodeRange(first) == firstCodeRange",
                "getCodeRange(second) == secondCodeRange",
                "getEncoding(first) == firstEncoding",
                "getEncoding(second) == secondEncoding"
        }, limit = "getCacheLimit()")
        protected Encoding negotiateRopeRopeCached(Rope first, Rope second,
                @Cached("getEncoding(first)") Encoding firstEncoding,
                @Cached("getEncoding(second)") Encoding secondEncoding,
                @Cached("isEmpty(first)") boolean isFirstEmpty,
                @Cached("isEmpty(second)") boolean isSecondEmpty,
                @Cached("getCodeRange(first)") CodeRange firstCodeRange,
                @Cached("getCodeRange(second)") CodeRange secondCodeRange,
                @Cached("negotiateRopeRopeUncached(first, second)") Encoding negotiatedEncoding) {
            return negotiatedEncoding;
        }

        @Specialization(guards = "getEncoding(first) != getEncoding(second)", replaces = "negotiateRopeRopeCached")
        protected Encoding negotiateRopeRopeUncached(Rope first, Rope second) {
            return compatibleEncodingForRopes(first, second);
        }

        protected Encoding getEncoding(Rope rope) {
            return rope.getEncoding();
        }

        protected CodeRange getCodeRange(Rope rope) {
            return rope.getCodeRange();
        }

        protected boolean isEmpty(Rope rope) {
            return rope.isEmpty();
        }

        @TruffleBoundary
        private static Encoding compatibleEncodingForRopes(Rope firstRope, Rope secondRope) {
            // Taken from org.jruby.RubyEncoding#areCompatible.

            final Encoding firstEncoding = firstRope.getEncoding();
            final Encoding secondEncoding = secondRope.getEncoding();

            if (secondRope.isEmpty())
                return firstEncoding;
            if (firstRope.isEmpty()) {
                return firstEncoding.isAsciiCompatible() && (secondRope.getCodeRange() == CodeRange.CR_7BIT) ? firstEncoding : secondEncoding;
            }

            if (!firstEncoding.isAsciiCompatible() || !secondEncoding.isAsciiCompatible())
                return null;

            if (firstRope.getCodeRange() != secondRope.getCodeRange()) {
                if (firstRope.getCodeRange() == CodeRange.CR_7BIT)
                    return secondEncoding;
                if (secondRope.getCodeRange() == CodeRange.CR_7BIT)
                    return firstEncoding;
            }
            if (secondRope.getCodeRange() == CodeRange.CR_7BIT)
                return firstEncoding;
            if (firstRope.getCodeRange() == CodeRange.CR_7BIT)
                return secondEncoding;

            return null;
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ENCODING_COMPATIBLE_QUERY_CACHE;
        }

    }

    @NodeChildren({ @NodeChild("first"), @NodeChild("second") })
    public static abstract class NegotiateCompatibleEncodingNode extends RubyNode {

        @Child private ToEncodingNode getEncodingNode = ToEncodingNode.create();

        public static NegotiateCompatibleEncodingNode create() {
            return EncodingNodesFactory.NegotiateCompatibleEncodingNodeGen.create(null, null);
        }

        public abstract Encoding executeNegotiate(Object first, Object second);

        @Specialization(guards = {
                "getEncoding(first) == getEncoding(second)",
                "getEncoding(first) == cachedEncoding"
        }, limit = "getCacheLimit()")
        protected Encoding negotiateSameEncodingCached(DynamicObject first, DynamicObject second,
                                                       @Cached("getEncoding(first)") Encoding cachedEncoding) {
            return cachedEncoding;
        }

        @Specialization(guards = "getEncoding(first) == getEncoding(second)", replaces = "negotiateSameEncodingCached")
        protected Encoding negotiateSameEncodingUncached(DynamicObject first, DynamicObject second) {
            return getEncoding(first);
        }

        @Specialization(guards = {
                "firstEncoding != secondEncoding",
                "isRubyString(first)",
                "isRubyString(second)",
                "isEmpty(first) == isFirstEmpty",
                "isEmpty(second) == isSecondEmpty",
                "getCodeRange(first) == firstCodeRange",
                "getCodeRange(second) == secondCodeRange",
                "getEncoding(first) == firstEncoding",
                "getEncoding(second) == secondEncoding"
        }, limit = "getCacheLimit()")
        protected Encoding negotiateStringStringCached(DynamicObject first, DynamicObject second,
                                                       @Cached("getEncoding(first)") Encoding firstEncoding,
                                                       @Cached("getEncoding(second)") Encoding secondEncoding,
                                                       @Cached("isEmpty(first)") boolean isFirstEmpty,
                                                       @Cached("isEmpty(second)") boolean isSecondEmpty,
                                                       @Cached("getCodeRange(first)") CodeRange firstCodeRange,
                                                       @Cached("getCodeRange(second)") CodeRange secondCodeRange,
                                                       @Cached("negotiateStringStringUncached(first, second)") Encoding negotiatedEncoding) {
            return negotiatedEncoding;
        }

        @Specialization(guards = {
                "getEncoding(first) != getEncoding(second)",
                "isRubyString(first)",
                "isRubyString(second)",
        }, replaces = "negotiateStringStringCached")
        protected Encoding negotiateStringStringUncached(DynamicObject first, DynamicObject second) {
            return compatibleEncodingForStrings(first, second);
        }

        @Specialization(guards = {
                "isRubyString(first)",
                "!isRubyString(second)",
                "getCodeRange(first) == codeRange",
                "getEncoding(first) == firstEncoding",
                "getEncoding(second) == secondEncoding",
                "firstEncoding != secondEncoding"
        }, limit = "getCacheLimit()")
        protected Encoding negotiateStringObjectCached(DynamicObject first, Object second,
                                                       @Cached("getEncoding(first)") Encoding firstEncoding,
                                                       @Cached("getEncoding(second)") Encoding secondEncoding,
                                                       @Cached("getCodeRange(first)") CodeRange codeRange,
                                                       @Cached("negotiateStringObjectUncached(first, second)") Encoding negotiatedEncoding) {
            return negotiatedEncoding;
        }

        @Specialization(guards = {
                "getEncoding(first) != getEncoding(second)",
                "isRubyString(first)",
                "!isRubyString(second)"
        }, replaces = "negotiateStringObjectCached")
        protected Encoding negotiateStringObjectUncached(DynamicObject first, Object second) {
            final Encoding firstEncoding = getEncoding(first);
            final Encoding secondEncoding = getEncoding(second);

            if (secondEncoding == null) {
                return null;
            }

            if (! firstEncoding.isAsciiCompatible() || ! secondEncoding.isAsciiCompatible()) {
                return null;
            }

            if (secondEncoding == USASCIIEncoding.INSTANCE) {
                return firstEncoding;
            }

            if (getCodeRange(first) == CodeRange.CR_7BIT) {
                return secondEncoding;
            }

            return null;
        }

        @Specialization(guards = {
                "getEncoding(first) != getEncoding(second)",
                "!isRubyString(first)",
                "isRubyString(second)"
        })
        protected Encoding negotiateObjectString(Object first, DynamicObject second) {
            return negotiateStringObjectUncached(second, first);
        }

        @Specialization(guards = {
                "firstEncoding != secondEncoding",
                "!isRubyString(first)",
                "!isRubyString(second)",
                "firstEncoding != null",
                "secondEncoding != null",
                "getEncoding(first) == firstEncoding",
                "getEncoding(second) == secondEncoding",
        }, limit = "getCacheLimit()")
        protected Encoding negotiateObjectObjectCached(Object first, Object second,
                                                            @Cached("getEncoding(first)") Encoding firstEncoding,
                                                            @Cached("getEncoding(second)") Encoding secondEncoding,
                                                            @Cached("areCompatible(firstEncoding, secondEncoding)") Encoding negotiatedEncoding) {

            return negotiatedEncoding;
        }

        @Specialization(guards = {
                "getEncoding(first) != getEncoding(second)",
                "!isRubyString(first)",
                "!isRubyString(second)"
        }, replaces = "negotiateObjectObjectCached")
        protected Encoding negotiateObjectObjectUncached(Object first, Object second) {
            final Encoding firstEncoding = getEncoding(first);
            final Encoding secondEncoding = getEncoding(second);

            return areCompatible(firstEncoding, secondEncoding);
        }


        private static Encoding compatibleEncodingForStrings(DynamicObject first, DynamicObject second) {
            // Taken from org.jruby.RubyEncoding#areCompatible.

            assert RubyGuards.isRubyString(first);
            assert RubyGuards.isRubyString(second);

            final Rope firstRope = StringOperations.rope(first);
            final Rope secondRope = StringOperations.rope(second);

            return compatibleEncodingForRopes(firstRope, secondRope);
        }

        @TruffleBoundary
        private static Encoding compatibleEncodingForRopes(Rope firstRope, Rope secondRope) {
            // Taken from org.jruby.RubyEncoding#areCompatible.

            final Encoding firstEncoding = firstRope.getEncoding();
            final Encoding secondEncoding = secondRope.getEncoding();

            if (secondRope.isEmpty()) return firstEncoding;
            if (firstRope.isEmpty()) {
                return firstEncoding.isAsciiCompatible() && (secondRope.getCodeRange() == CodeRange.CR_7BIT) ? firstEncoding : secondEncoding;
            }

            if (!firstEncoding.isAsciiCompatible() || !secondEncoding.isAsciiCompatible()) return null;

            if (firstRope.getCodeRange() != secondRope.getCodeRange()) {
                if (firstRope.getCodeRange() == CodeRange.CR_7BIT) return secondEncoding;
                if (secondRope.getCodeRange() == CodeRange.CR_7BIT) return firstEncoding;
            }
            if (secondRope.getCodeRange() == CodeRange.CR_7BIT) return firstEncoding;
            if (firstRope.getCodeRange() == CodeRange.CR_7BIT) return secondEncoding;

            return null;
        }

        @TruffleBoundary
        protected static Encoding areCompatible(Encoding enc1, Encoding enc2) {
            assert enc1 != enc2;

            if (enc1 == null || enc2 == null) return null;

            if (!enc1.isAsciiCompatible() || !enc2.isAsciiCompatible()) return null;

            if (enc2 instanceof USASCIIEncoding) return enc1;
            if (enc1 instanceof USASCIIEncoding) return enc2;

            return null;
        }

        protected boolean isEmpty(Object string) {
            // The Truffle DSL generator will calculate @Cached values used in guards above all guards. In practice,
            // this guard is only used on Ruby strings, but the method must handle any object type because of the form
            // of the generated code. If the object is not a Ruby string, the resulting value is never used.
            if (RubyGuards.isRubyString(string)) {
                return StringOperations.rope((DynamicObject) string).isEmpty();
            }

            return false;
        }

        protected CodeRange getCodeRange(Object string) {
            // The Truffle DSL generator will calculate @Cached values used in guards above all guards. In practice,
            // this guard is only used on Ruby strings, but the method must handle any object type because of the form
            // of the generated code. If the object is not a Ruby string, the resulting value is never used.
            if (RubyGuards.isRubyString(string)) {
                return StringOperations.rope((DynamicObject) string).getCodeRange();
            }

            return CodeRange.CR_UNKNOWN;
        }

        protected Encoding getEncoding(Object value) {
            return getEncodingNode.executeToEncoding(value);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ENCODING_COMPATIBLE_QUERY_CACHE;
        }

    }

    @CoreMethod(names = "compatible?", onSingleton = true, required = 2)
    public abstract static class CompatibleQueryNode extends CoreMethodArrayArgumentsNode {

        @Child private GetRubyEncodingNode getRubyEncodingNode = EncodingNodesFactory.GetRubyEncodingNodeGen.create();
        @Child private NegotiateCompatibleEncodingNode negotiateCompatibleEncodingNode = NegotiateCompatibleEncodingNode.create();

        public abstract DynamicObject executeCompatibleQuery(Object first, Object second);

        @Specialization
        protected DynamicObject isCompatible(Object first, Object second,
                                             @Cached("createBinaryProfile()") ConditionProfile noNegotiatedEncodingProfile) {
            final Encoding negotiatedEncoding = negotiateCompatibleEncodingNode.executeNegotiate(first, second);

            if (noNegotiatedEncodingProfile.profile(negotiatedEncoding == null)) {
                return nil();
            }

            return getRubyEncodingNode.executeGetRubyEncoding(negotiatedEncoding);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ENCODING_COMPATIBLE_QUERY_CACHE;
        }

    }

    @CoreMethod(names = "list", onSingleton = true)
    public abstract static class ListNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject list() {
            final Object[] encodingsList = getContext().getEncodingManager().getEncodingList();
            return createArray(encodingsList, encodingsList.length);
        }
    }


    @CoreMethod(names = "locale_charmap", onSingleton = true)
    public abstract static class LocaleCharacterMapNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject localeCharacterMap(@Cached("create()") GetRubyEncodingNode getRubyEncodingNode) {
            final DynamicObject rubyEncoding = getRubyEncodingNode.executeGetRubyEncoding(getContext().getEncodingManager().getLocaleEncoding());

            return Layouts.ENCODING.getName(rubyEncoding);
        }
    }

    @CoreMethod(names = "dummy?")
    public abstract static class DummyNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = "encoding == cachedEncoding", limit = "getCacheLimit()")
        public boolean isDummyCached(DynamicObject encoding,
                                     @Cached("encoding") DynamicObject cachedEncoding,
                                     @Cached("isDummy(cachedEncoding)") boolean isDummy) {
            return isDummy;
        }

        @Specialization(replaces = "isDummyCached")
        public boolean isDummyUncached(DynamicObject encoding) {
            return isDummy(encoding);
        }

        protected int getCacheLimit() {
            return getContext().getOptions().ENCODING_LOADED_CLASSES_CACHE;
        }

        protected static boolean isDummy(DynamicObject encoding) {
            assert RubyGuards.isRubyEncoding(encoding);

            return Layouts.ENCODING.getDummy(encoding);
        }
    }

    @CoreMethod(names = { "name", "to_s" })
    public abstract static class ToSNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject toS(DynamicObject encoding) {
            return Layouts.ENCODING.getName(encoding);
        }

    }

    @CoreMethod(names = "__allocate__", constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class AllocateNode extends UnaryCoreMethodNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            throw new RaiseException(coreExceptions().typeErrorAllocatorUndefinedFor(rubyClass, this));
        }

    }

    @Primitive(name = "encoding_enc_find_index", needsSelf = false)
    public static abstract class EncodingFindIndexNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyString(nameObject)")
        public int encodingFindIndex(DynamicObject nameObject) {
            final String name = StringOperations.getString(nameObject);
            final DynamicObject encodingObject = getContext().getEncodingManager().getRubyEncoding(name);
            return encodingObject != null ? Layouts.ENCODING.getEncoding(encodingObject).getIndex() : -1;
        }

    }

    @Primitive(name = "encoding_get_object_encoding", needsSelf = false)
    public static abstract class EncodingGetObjectEncodingNode extends PrimitiveArrayArgumentsNode {

        @Child private GetRubyEncodingNode getRubyEncodingNode = EncodingNodesFactory.GetRubyEncodingNodeGen.create();

        @Specialization(guards = "isRubyString(string)")
        public DynamicObject encodingGetObjectEncodingString(DynamicObject string) {
            return getRubyEncodingNode.executeGetRubyEncoding(Layouts.STRING.getRope(string).getEncoding());
        }

        @Specialization(guards = "isRubySymbol(symbol)")
        public DynamicObject encodingGetObjectEncodingSymbol(DynamicObject symbol) {
            return getRubyEncodingNode.executeGetRubyEncoding(Layouts.SYMBOL.getRope(symbol).getEncoding());
        }

        @Specialization(guards = "isRubyEncoding(encoding)")
        public DynamicObject encodingGetObjectEncoding(DynamicObject encoding) {
            return encoding;
        }

        @Specialization(guards = "isRubyRegexp(regexp)")
        public DynamicObject encodingGetObjectEncodingRegexp(DynamicObject regexp) {
            return getRubyEncodingNode.executeGetRubyEncoding(Layouts.REGEXP.getSource(regexp).getEncoding());
        }

        @Specialization(guards = {"!isRubyString(object)", "!isRubySymbol(object)", "!isRubyEncoding(object)", "!isRubyRegexp(object)"})
        public DynamicObject encodingGetObjectEncodingNil(DynamicObject object) {
            // TODO(CS, 26 Jan 15) something to do with __encoding__ here?
            return nil();
        }

    }

    @Primitive(name = "encoding_replicate")
    public static abstract class EncodingReplicateNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isRubyString(nameObject)")
        public DynamicObject encodingReplicate(VirtualFrame frame, DynamicObject self, DynamicObject nameObject,
                @Cached("new()") SnippetNode snippetNode) {
            final String name = StringOperations.getString(nameObject);
            final Encoding encoding = EncodingOperations.getEncoding(self);

            final DynamicObject newEncoding = getContext().getEncodingManager().replicateEncoding(encoding, name);
            if (newEncoding == null) {
                throw new RaiseException(coreExceptions().argumentErrorEncodingAlreadyRegistered(name, this));
            }

            final Entry entry = EncodingDB.getEncodings().get(name.getBytes());
            snippetNode.execute(frame, "Encoding::EncodingMap[enc.name.upcase.to_sym] = [nil, index]", "enc", newEncoding, "index", entry.getIndex());
            return newEncoding;
        }

    }

    @Primitive(name = "encoding_get_encoding_by_index", needsSelf = false, lowerFixnum = 1)
    public static abstract class GetEncodingObjectByIndexNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public DynamicObject getEncoding(int index) {
            return getContext().getEncodingManager().getRubyEncoding(index);
        }

    }

    @NodeChildren({ @NodeChild("first"), @NodeChild("second") })
    public static abstract class CheckEncodingNode extends RubyNode {

        @Child private NegotiateCompatibleEncodingNode negotiateCompatibleEncodingNode;
        @Child private ToEncodingNode toEncodingNode;

        public static CheckEncodingNode create() {
            return EncodingNodesFactory.CheckEncodingNodeGen.create(null, null);
        }

        public CheckEncodingNode() {
            negotiateCompatibleEncodingNode = EncodingNodesFactory.NegotiateCompatibleEncodingNodeGen.create(null, null);
        }

        public abstract Encoding executeCheckEncoding(Object first, Object second);

        @Specialization
        public Encoding checkEncoding(Object first, Object second,
                                      @Cached("create()") BranchProfile errorProfile) {
            final Encoding negotiatedEncoding = negotiateCompatibleEncodingNode.executeNegotiate(first, second);

            if (negotiatedEncoding == null) {
                errorProfile.enter();

                if (toEncodingNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    toEncodingNode = insert(ToEncodingNode.create());
                }

                throw new RaiseException(getContext().getCoreExceptions().encodingCompatibilityErrorIncompatible(
                        toEncodingNode.executeToEncoding(first), toEncodingNode.executeToEncoding(second), this));
            }

            return negotiatedEncoding;
        }

    }

}

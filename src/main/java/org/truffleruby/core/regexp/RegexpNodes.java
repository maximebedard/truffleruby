/*
 * Copyright (c) 2016, 2018 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
/*
 * Copyright (c) 2013, 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.regexp;

import java.util.Arrays;
import java.util.Iterator;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.NameEntry;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Region;
import org.joni.Syntax;
import org.joni.exception.SyntaxException;
import org.joni.exception.ValueException;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.builtins.CallerFrameAccess;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.NonStandard;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.core.cast.TaintResultNode;
import org.truffleruby.core.cast.ToStrNode;
import org.truffleruby.core.cast.ToStrNodeGen;
import org.truffleruby.core.regexp.RegexpNodesFactory.MatchNodeGen;
import org.truffleruby.core.regexp.RegexpNodesFactory.RegexpSetLastMatchPrimitiveNodeFactory;
import org.truffleruby.core.regexp.RegexpNodesFactory.ToSNodeFactory;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.ReadCallerFrameNode;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.objects.AllocateObjectNode;
import org.truffleruby.language.threadlocal.FindThreadAndFrameLocalStorageNode;
import org.truffleruby.language.threadlocal.FindThreadAndFrameLocalStorageNodeGen;
import org.truffleruby.language.threadlocal.ThreadAndFrameLocalStorage;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectFactory;
import com.oracle.truffle.api.profiles.ConditionProfile;

@CoreClass("Regexp")
public abstract class RegexpNodes {

    public static final String LAST_MATCH_VARIABLE = "$~";

    @TruffleBoundary
    public static Matcher createMatcher(RubyContext context, DynamicObject regexp, Rope stringRope, byte[] stringBytes, boolean encodingConversion) {
        final Encoding enc = checkEncoding(regexp, stringRope, true);
        Regex regex = Layouts.REGEXP.getRegex(regexp);

        if (encodingConversion && regex.getEncoding() != enc) {
            EncodingCache encodingCache = Layouts.REGEXP.getCachedEncodings(regexp);
            regex = encodingCache.getOrCreate(enc, e -> makeRegexpForEncoding(context, regexp, e));
        }

        return regex.matcher(stringBytes, 0, stringBytes.length);
    }

    private static Regex makeRegexpForEncoding(RubyContext context, DynamicObject regexp, final Encoding enc) {
        Regex regex;
        final Encoding[] fixedEnc = new Encoding[] { null };
        final Rope sourceRope = Layouts.REGEXP.getSource(regexp);
        final RopeBuilder preprocessed = ClassicRegexp.preprocess(context, sourceRope, enc, fixedEnc, RegexpSupport.ErrorMode.RAISE);
        final RegexpOptions options = Layouts.REGEXP.getOptions(regexp);
        regex = new Regex(preprocessed.getUnsafeBytes(), 0, preprocessed.getLength(),
                options.toJoniOptions(), enc);
        return regex;
    }

    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "regexp"),
            @NodeChild(type = RubyNode.class, value = "source"),
            @NodeChild(type = RubyNode.class, value = "matcher"),
            @NodeChild(type = RubyNode.class, value = "startPos"),
            @NodeChild(type = RubyNode.class, value = "range"),
            @NodeChild(type = RubyNode.class, value = "onlyAtStart")
    })
    public static abstract class MatchNode extends RubyNode {
        @Child private TaintResultNode taintResultNode = new TaintResultNode();
        @Child private AllocateObjectNode allocateNode = AllocateObjectNode.create();
        @Child CallDispatchHeadNode dupNode = CallDispatchHeadNode.create();

        public static MatchNode create() {
            return MatchNodeGen.create(null, null, null, null, null, null);
        }

        public abstract DynamicObject execute(VirtualFrame frame, DynamicObject regexp, DynamicObject string, Matcher matcher,
                int startPos, int range, boolean onlyMatchAtStart);

        // Creating a MatchData will store a copy of the source string. It's tempting to use a rope here, but a bit
        // inconvenient because we can't work with ropes directly in Ruby and some MatchData methods are nicely
        // implemented using the source string data. Likewise, we need to taint objects based on the source string's
        // taint state. We mustn't allow the source string's contents to change, however, so we must ensure that we have
        // a private copy of that string. Since the source string would otherwise be a reference to string held outside
        // the MatchData object, it would be possible for the source string to be modified externally.
        //
        // Ex. x = "abc"; x =~ /(.*)/; x.upcase!
        //
        // Without a private copy, the MatchData's source could be modified to be upcased when it should remain the
        // same as when the MatchData was created.
        @Specialization
        protected DynamicObject executeMatch(VirtualFrame frame, DynamicObject regexp, DynamicObject string, Matcher matcher,
                int startPos, int range, boolean onlyMatchAtStart,
                @Cached("createBinaryProfile()") ConditionProfile matchesProfile) {
            assert RubyGuards.isRubyRegexp(regexp);
            assert RubyGuards.isRubyString(string);

            int match = runMatch(matcher, startPos, range, onlyMatchAtStart);

            if (matchesProfile.profile(match == -1)) {
                return nil();
            }

            assert match >= 0;

            final Region region = matcher.getEagerRegion();
            final DynamicObject dupedString = (DynamicObject) dupNode.call(frame, string, "dup");
            DynamicObject result = allocateNode.allocate(matchDataClass(), Layouts.MATCH_DATA.build(dupedString,
                    regexp, region, null));
            return (DynamicObject) taintResultNode.maybeTaint(string, result);
        }

        @TruffleBoundary
        private int runMatch(Matcher matcher, int startPos, int range, boolean onlyMatchAtStart) {
            // Keep status as RUN because MRI has an uninterruptible Regexp engine
            if (onlyMatchAtStart) {
                return getContext().getThreadManager().runUntilResultKeepStatus(this,
                        () -> matcher.matchInterruptible(startPos, range, Option.DEFAULT));
            } else {
                return getContext().getThreadManager().runUntilResultKeepStatus(this,
                        () -> matcher.searchInterruptible(startPos, range, Option.DEFAULT));
            }
        }

        protected DynamicObject matchDataClass() {
            return getContext().getCoreLibrary().getMatchDataClass();
        }
    }

    public static Rope shimModifiers(Rope bytes) {
        // Joni doesn't support (?u) etc but we can shim some common cases

        String bytesString = RopeOperations.decodeRope(bytes);

        if (bytesString.startsWith("(?u)") || bytesString.startsWith("(?d)") || bytesString.startsWith("(?a)")) {
            final char modifier = (char) bytes.get(2);
            bytesString = bytesString.substring(4);

            switch (modifier) {
                case 'u': {
                    bytesString = StringUtils.replace(bytesString, "\\w", "[[:alpha:]]");
                } break;

                case 'd': {

                } break;

                case 'a': {
                    bytesString = StringUtils.replace(bytesString, "[[:alpha:]]", "[a-zA-Z]");
                } break;

                default:
                    throw new UnsupportedOperationException();
            }

            bytes = StringOperations.encodeRope(bytesString, bytes.getEncoding());
        }

        return bytes;
    }

    @TruffleBoundary
    public static Regex compile(Node currentNode, RubyContext context, Rope bytes, RegexpOptions options) {
        bytes = shimModifiers(bytes);

        try {
            Encoding enc = bytes.getEncoding();
            Encoding[] fixedEnc = new Encoding[]{null};
            RopeBuilder unescaped = ClassicRegexp.preprocess(context, bytes, enc, fixedEnc, RegexpSupport.ErrorMode.RAISE);
            if (fixedEnc[0] != null) {
                if ((fixedEnc[0] != enc && options.isFixed()) ||
                        (fixedEnc[0] != ASCIIEncoding.INSTANCE && options.isEncodingNone())) {
                    throw new RaiseException(context.getCoreExceptions().regexpError("incompatible character encoding", null));
                }
                if (fixedEnc[0] != ASCIIEncoding.INSTANCE) {
                    options.setFixed(true);
                    enc = fixedEnc[0];
                }
            } else if (!options.isFixed()) {
                enc = USASCIIEncoding.INSTANCE;
            }

            if (fixedEnc[0] != null) {
                options.setFixed(true);
            }
            //if (regexpOptions.isEncodingNone()) setEncodingNone();

            Regex regexp = new Regex(unescaped.getUnsafeBytes(), 0, unescaped.getLength(), options.toJoniOptions(), enc, Syntax.RUBY);
            regexp.setUserObject(RopeOperations.withEncodingVerySlow(bytes, enc));

            return regexp;
        } catch (ValueException e) {
            throw new RaiseException(context.getCoreExceptions().runtimeError("error compiling regex", currentNode));
        } catch (SyntaxException e) {
            throw new RaiseException(context.getCoreExceptions().regexpError(e.getMessage(), currentNode));
        }
    }

    public static void setRegex(DynamicObject regexp, Regex regex) {
        Layouts.REGEXP.setRegex(regexp, regex);
        Layouts.REGEXP.setCachedEncodings(regexp, new EncodingCache());
    }

    public static void setSource(DynamicObject regexp, Rope source) {
        Layouts.REGEXP.setSource(regexp, source);
    }

    public static void setOptions(DynamicObject regexp, RegexpOptions options) {
        Layouts.REGEXP.setOptions(regexp, options);
    }

    public static Encoding checkEncoding(DynamicObject regexp, Rope str, boolean warn) {
        return checkEncoding(regexp, str.getEncoding(), str.getCodeRange(), warn);
    }

    // TODO (nirvdrum 03-June-15) Unify with JRuby in RegexpSupport.
    public static Encoding checkEncoding(DynamicObject regexp, Encoding strEnc, CodeRange codeRange, boolean warn) {
        assert RubyGuards.isRubyRegexp(regexp);

        final Encoding regexEnc = Layouts.REGEXP.getRegex(regexp).getEncoding();

        /*
        if (str.scanForCodeRange() == StringSupport.CR_BROKEN) {
            throw getRuntime().newArgumentError("invalid byte sequence in " + str.getEncoding());
        }
        */
        //check();
        if (strEnc == regexEnc) {
            return regexEnc;
        } else if (regexEnc == USASCIIEncoding.INSTANCE && codeRange == CodeRange.CR_7BIT) {
            return regexEnc;
        } else if (!strEnc.isAsciiCompatible()) {
            if (strEnc != regexEnc) {
                //encodingMatchError(getRuntime(), pattern, enc);
            }
        } else if (Layouts.REGEXP.getOptions(regexp).isFixed()) {
            /*
            if (enc != pattern.getEncoding() &&
                    (!pattern.getEncoding().isAsciiCompatible() ||
                            str.scanForCodeRange() != StringSupport.CR_7BIT)) {
                encodingMatchError(getRuntime(), pattern, enc);
            }
            */
            return regexEnc;
        }
        /*
        if (warn && this.options.isEncodingNone() && enc != ASCIIEncoding.INSTANCE && str.scanForCodeRange() != StringSupport.CR_7BIT) {
            getRuntime().getWarnings().warn(ID.REGEXP_MATCH_AGAINST_STRING, "regexp match /.../n against to " + enc + " string");
        }
        */
        return strEnc;
    }

    public static void initialize(RubyContext context, DynamicObject regexp, Node currentNode, Rope setSource, int options) {
        assert RubyGuards.isRubyRegexp(regexp);
        final RegexpOptions regexpOptions = RegexpOptions.fromEmbeddedOptions(options);
        final Regex regex = compile(currentNode, context, setSource, regexpOptions);

        // The RegexpNodes.compile operation may modify the encoding of the source rope. This modified copy is stored
        // in the Regex object as the "user object". Since ropes are immutable, we need to take this updated copy when
        // constructing the final regexp.
        setSource(regexp, (Rope) regex.getUserObject());
        setOptions(regexp, regexpOptions);
        setRegex(regexp, regex);
    }

    public static void initialize(DynamicObject regexp, Regex setRegex, Rope setSource) {
        assert RubyGuards.isRubyRegexp(regexp);
        setRegex(regexp, setRegex);
        setSource(regexp, setSource);
    }

    public static DynamicObject createRubyRegexp(RubyContext context, Node currentNode, DynamicObjectFactory factory, Rope source, RegexpOptions options) {
        final Regex regexp = RegexpNodes.compile(currentNode, context, source, options);

        // The RegexpNodes.compile operation may modify the encoding of the source rope. This modified copy is stored
        // in the Regex object as the "user object". Since ropes are immutable, we need to take this updated copy when
        // constructing the final regexp.
        return Layouts.REGEXP.createRegexp(factory, regexp, (Rope) regexp.getUserObject(), options, new EncodingCache());
    }

    @TruffleBoundary
    public static DynamicObject createRubyRegexp(DynamicObjectFactory factory, Regex regex, Rope source, RegexpOptions options) {
        final DynamicObject regexp = Layouts.REGEXP.createRegexp(factory, null, null, RegexpOptions.NULL_OPTIONS, null);
        RegexpNodes.setOptions(regexp, options);
        RegexpNodes.initialize(regexp, regex, source);
        return regexp;
    }

    public static boolean isSuitableMatchDataType(RubyContext context, DynamicObject matchData) {
        return matchData == context.getCoreLibrary().getNil() || RubyGuards.isRubyMatchData(matchData);
    }

    public static boolean frameIsNotSend(RubyContext context, Object callerFrame) {
        InternalMethod method = RubyArguments.tryGetMethod((Frame) callerFrame);
        return !context.getCoreLibrary().isSend(method);
    }

    @CoreMethod(names = "hash")
    public abstract static class HashNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public int hash(DynamicObject regexp) {
            int options = Layouts.REGEXP.getRegex(regexp).getOptions() & ~32 /* option n, NO_ENCODING in common/regexp.rb */;
            return options ^ Layouts.REGEXP.getSource(regexp).hashCode();
        }

    }

    @NonStandard
    @CoreMethod(names = "match_start", required = 2, lowerFixnum = 2)
    public abstract static class MatchStartNode extends CoreMethodArrayArgumentsNode {

        @Child private MatchNode matchNode = MatchNode.create();
        @Child private RopeNodes.BytesNode bytesNode = RopeNodes.BytesNode.create();

        @Specialization(guards = "isRubyString(string)")
        public Object matchStart(VirtualFrame frame, DynamicObject regexp, DynamicObject string, int startPos) {
            final Rope rope = StringOperations.rope(string);
            final Matcher matcher = createMatcher(getContext(), regexp, rope, bytesNode.execute(rope), true);
            int range = rope.byteLength();
            return matchNode.execute(frame, regexp, string, matcher, startPos, range, true);
        }
    }

    @Primitive(name = "regexp_set_last_match", needsSelf = false)
    @ImportStatic(RegexpNodes.class)
    public static abstract class RegexpSetLastMatchPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Child ReadCallerFrameNode readCallerFrame = new ReadCallerFrameNode(CallerFrameAccess.READ_WRITE);
        @Child FindThreadAndFrameLocalStorageNode threadLocalNode;
        @CompilationFinal DynamicObject lastMatchSymbol;

        public static RegexpSetLastMatchPrimitiveNode create() {
            return RegexpSetLastMatchPrimitiveNodeFactory.create(null);
        }

        public abstract DynamicObject executeSetLastMatch(VirtualFrame frame, Object matchData);

        @Specialization(guards = "isSuitableMatchDataType(getContext(), matchData)")
        public DynamicObject setLastMatchData(VirtualFrame frame, DynamicObject matchData) {
            if (threadLocalNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                lastMatchSymbol = getContext().getSymbolTable().getSymbol(LAST_MATCH_VARIABLE);
                threadLocalNode = insert(FindThreadAndFrameLocalStorageNodeGen.create());
            }
            Frame callerFrame = readCallerFrame.execute(frame);
            ThreadAndFrameLocalStorage lastMatch = threadLocalNode.execute(lastMatchSymbol, callerFrame.materialize());
            lastMatch.set(matchData);
            return matchData;
        }
    }

    @Primitive(name = "regexp_set_block_last_match", needsSelf = false)
    @ImportStatic(RegexpNodes.class)
    public static abstract class RegexpSetBlockLastMatchPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Child FindThreadAndFrameLocalStorageNode threadLocalNode;
        @CompilationFinal DynamicObject lastMatchSymbol;

        @Specialization(guards = { "isRubyProc(block)", "isSuitableMatchDataType(getContext(), matchData)" })
        public Object setBlockLastMatch(DynamicObject block, DynamicObject matchData) {
            final Frame declarationFrame = Layouts.PROC.getDeclarationFrame(block);
            if (declarationFrame == null) { // Symbol#to_proc currently does not have a declaration frame
                return matchData;
            }
            if (threadLocalNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                lastMatchSymbol = getContext().getSymbolTable().getSymbol(LAST_MATCH_VARIABLE);
                threadLocalNode = insert(FindThreadAndFrameLocalStorageNodeGen.create());
            }

            ThreadAndFrameLocalStorage lastMatch = threadLocalNode.execute(lastMatchSymbol, declarationFrame.materialize());
            lastMatch.set(matchData);
            return matchData;
        }

    }

    private static Frame getMatchDataFrameSearchingStack() {
        return Truffle.getRuntime().iterateFrames(frameInstance -> {
            final Frame frame = frameInstance.getFrame(FrameAccess.READ_ONLY);
            InternalMethod method = RubyArguments.tryGetMethod(frame);

            if (method == null) {
                // not a Ruby method, continue
                return null;
            } else {
                final Frame frameOfDeclaration = RegexpNodes.
                        getMatchDataFrameSearchingDeclarations(frame, false);
                if (frameOfDeclaration == null) {
                    // Does not have a $~ slot, continue
                    return null;
                } else {
                    return frameOfDeclaration;
                }
            }
        });
    }

    private static Frame getMatchDataFrameSearchingDeclarations(Frame topFrame, boolean returnCandidate) {
        Frame frame = topFrame;

        while (true) {
            final FrameSlot slot = getMatchDataSlot(frame);
            if (slot != null) {
                break;
            }

            final Frame nextFrame = RubyArguments.getDeclarationFrame(frame);
            if (nextFrame != null) {
                frame = nextFrame;
            } else {
                // where to define when missing
                return returnCandidate ? frame : null;
            }
        }

        return frame;
    }

    @TruffleBoundary
    private static FrameSlot getMatchDataSlot(Frame frame) {
        return frame.getFrameDescriptor().findFrameSlot("$~");
    }

    @TruffleBoundary
    public static ThreadAndFrameLocalStorage getMatchDataThreadLocalSearchingStack(RubyContext context) {
        final Frame frame = getMatchDataFrameSearchingStack();
        return getThreadLocalObject(context, frame, false);
    }

    @TruffleBoundary
    public static ThreadAndFrameLocalStorage getThreadLocalObject(RubyContext context, Frame frame, boolean add) {
        if (frame == null) {
            return null;
        }

        FrameSlot slot = getMatchDataSlot(frame);
        if (slot == null) {
            // slot can be null only when add is true
            slot = frame.getFrameDescriptor().addFrameSlot("$~", FrameSlotKind.Object);
        }

        return getThreadLocalObjectFromFrame(context, frame, slot, frame.getFrameDescriptor().getDefaultValue(), add);
    }

    private static ThreadAndFrameLocalStorage getThreadLocalObjectFromFrame(RubyContext context, Frame frame, FrameSlot slot, Object defaultValue, boolean add) {
        final Object previousMatchData = frame.getValue(slot);

        if (previousMatchData == defaultValue) { // Never written to
            if (add) {
                ThreadAndFrameLocalStorage threadLocalObject = new ThreadAndFrameLocalStorage(context);
                frame.setObject(slot, threadLocalObject);
                return threadLocalObject;
            } else {
                return null;
            }
        }

        return (ThreadAndFrameLocalStorage) previousMatchData;
    }

    @CoreMethod(names = { "quote", "escape" }, onSingleton = true, required = 1)
    public abstract static class QuoteNode extends CoreMethodArrayArgumentsNode {

        @Child private StringNodes.MakeStringNode makeStringNode;
        @Child private ToStrNode toStrNode;

        abstract public DynamicObject executeQuote(VirtualFrame frame, Object raw);

        @TruffleBoundary
        @Specialization(guards = "isRubyString(raw)")
        public DynamicObject quoteString(DynamicObject raw) {
            final Rope rope = StringOperations.rope(raw);
            boolean isAsciiOnly = rope.getEncoding().isAsciiCompatible() && rope.getCodeRange() == CodeRange.CR_7BIT;
            return getMakeStringNode().fromRope(ClassicRegexp.quote19(rope, isAsciiOnly));
        }

        @Specialization(guards = "isRubySymbol(raw)")
        public DynamicObject quoteSymbol(DynamicObject raw) {
            return quoteString(getMakeStringNode().executeMake(Layouts.SYMBOL.getString(raw), UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN));
        }

        @Fallback
        public DynamicObject quote(VirtualFrame frame, Object raw) {
            if (toStrNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toStrNode = insert(ToStrNodeGen.create(null));
            }

            return executeQuote(frame, toStrNode.executeToStr(frame, raw));
        }

        private StringNodes.MakeStringNode getMakeStringNode() {
            if (makeStringNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                makeStringNode = insert(StringNodes.MakeStringNode.create());
            }

            return makeStringNode;
        }
    }

    @NonStandard
    @CoreMethod(names = "search_from", required = 2, lowerFixnum = 2)
    public abstract static class SearchFromNode extends CoreMethodArrayArgumentsNode {

        @Child private MatchNode matchNode = MatchNode.create();
        @Child private RopeNodes.BytesNode bytesNode = RopeNodes.BytesNode.create();

        @Specialization(guards = "isRubyString(string)")
        public Object searchFrom(VirtualFrame frame, DynamicObject regexp, DynamicObject string, int startPos) {
            final Rope rope = StringOperations.rope(string);
            final Matcher matcher = createMatcher(getContext(), regexp, rope, bytesNode.execute(rope), true);
            int range = StringOperations.rope(string).byteLength();

            return matchNode.execute(frame, regexp, string, matcher, startPos, range, false);
        }
    }

    @Primitive(name = "regexp_search_from_binary", lowerFixnum = 2)
    public abstract static class SearchFromBinaryNode extends CoreMethodArrayArgumentsNode {

        @Child private MatchNode matchNode = MatchNode.create();
        @Child private RopeNodes.BytesNode bytesNode = RopeNodes.BytesNode.create();

        @Specialization(guards = "isRubyString(string)")
        public Object searchFrom(VirtualFrame frame, DynamicObject regexp, DynamicObject string, int startPos) {
            final Rope rope = StringOperations.rope(string);
            final Matcher matcher = createMatcher(getContext(), regexp, rope, bytesNode.execute(rope), false);
            final int endPos = rope.byteLength();
            return matchNode.execute(frame, regexp, string, matcher, startPos, endPos, false);
        }
    }

    @CoreMethod(names = "source")
    public abstract static class SourceNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject source(DynamicObject regexp,
                                    @Cached("create()") StringNodes.MakeStringNode makeStringNode) {
            return makeStringNode.fromRope(Layouts.REGEXP.getSource(regexp));
        }

    }

    @CoreMethod(names = "to_s")
    @ImportStatic(RegexpGuards.class)
    public abstract static class ToSNode extends CoreMethodArrayArgumentsNode {

        @Child private StringNodes.MakeStringNode makeStringNode = StringNodes.MakeStringNode.create();

        public static ToSNode create() {
            return ToSNodeFactory.create(null);
        }

        public abstract DynamicObject execute(DynamicObject regexp);

        @Specialization(guards = "isSameRegexp(regexp,cachedRegexp)")
        public DynamicObject toSCached(DynamicObject regexp,
                @Cached("regexp") DynamicObject cachedRegexp,
                @Cached("createRope(regexp)") Rope rope) {
            return makeStringNode.fromRope(rope);
        }

        @Specialization
        public DynamicObject toS(DynamicObject regexp) {
            final Rope rope = createRope(regexp);
            return makeStringNode.fromRope(rope);
        }

        @TruffleBoundary
        protected Rope createRope(DynamicObject regexp) {
            final ClassicRegexp classicRegexp = ClassicRegexp.newRegexp(getContext(), Layouts.REGEXP.getSource(regexp), Layouts.REGEXP.getRegex(regexp).getOptions());
            return classicRegexp.toRopeBuilder().toRope();
        }
    }

    @Primitive(name = "regexp_names")
    public abstract static class RegexpNamesNode extends PrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public Object regexpNames(DynamicObject regexp) {
            final int size = Layouts.REGEXP.getRegex(regexp).numberOfNames();
            if (size == 0) {
                return createArray(null, size);
            }

            final Object[] names = new Object[size];
            int i = 0;
            for (final Iterator<NameEntry> iter = Layouts.REGEXP.getRegex(regexp).namedBackrefIterator(); iter.hasNext();) {
                final NameEntry e = iter.next();
                final byte[] bytes = Arrays.copyOfRange(e.name, e.nameP, e.nameEnd);
                final Rope rope = getContext().getRopeCache().getRope(bytes, USASCIIEncoding.INSTANCE, CodeRange.CR_7BIT);
                final DynamicObject name = getContext().getFrozenStrings().getFrozenString(rope);

                final int[] backrefs = e.getBackRefs();
                final DynamicObject backrefsRubyArray = createArray(backrefs, backrefs.length);
                names[i++] = createArray(new Object[]{ name, backrefsRubyArray }, 2);
            }

            return createArray(names, size);
        }

    }

    @CoreMethod(names = "__allocate__", constructor = true, visibility = Visibility.PRIVATE)
    public abstract static class AllocateNode extends CoreMethodArrayArgumentsNode {

        @Child private AllocateObjectNode allocateNode = AllocateObjectNode.create();

        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            return allocateNode.allocate(rubyClass, null, null, RegexpOptions.NULL_OPTIONS, null);
        }

    }

    @Primitive(name = "regexp_fixed_encoding_p")
    public static abstract class RegexpFixedEncodingPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public boolean fixedEncoding(DynamicObject regexp) {
            return Layouts.REGEXP.getOptions(regexp).isFixed();
        }

    }

    @Primitive(name = "regexp_initialize", lowerFixnum = 2)
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpInitializePrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = { "isRegexpLiteral(regexp)", "isRubyString(pattern)" })
        public DynamicObject initializeRegexpLiteral(DynamicObject regexp, DynamicObject pattern, int options) {
            throw new RaiseException(coreExceptions().securityError("can't modify literal regexp", this));
        }

        @Specialization(guards = { "!isRegexpLiteral(regexp)", "isInitialized(regexp)", "isRubyString(pattern)" })
        public DynamicObject initializeAlreadyInitialized(DynamicObject regexp, DynamicObject pattern, int options) {
            throw new RaiseException(coreExceptions().typeError("already initialized regexp", this));
        }

        @Specialization(guards = { "!isRegexpLiteral(regexp)", "!isInitialized(regexp)", "isRubyString(pattern)" })
        public DynamicObject initialize(DynamicObject regexp, DynamicObject pattern, int options) {
            RegexpNodes.initialize(getContext(), regexp, this, StringOperations.rope(pattern), options);
            return regexp;
        }
    }

    @CoreMethod(names = "initialize_copy", required = 1, needsSelf = true)
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpInitializeCopyPrimitiveNode extends CoreMethodArrayArgumentsNode {

        @Specialization(guards = { "isRegexpLiteral(regexp)", "isRubyRegexp(other)" })
        public DynamicObject initializeRegexpLiteral(DynamicObject regexp, DynamicObject other) {
            throw new RaiseException(coreExceptions().securityError("can't modify literal regexp", this));
        }

        @Specialization(guards = { "!isRegexpLiteral(regexp)", "isInitialized(regexp)", "isRubyRegexp(other)" })
        public DynamicObject initializeAlreadyInitialized(DynamicObject regexp, DynamicObject other) {
            throw new RaiseException(coreExceptions().typeError("already initialized regexp", this));
        }

        @Specialization(guards = { "!isRegexpLiteral(regexp)", "!isInitialized(regexp)", "isRubyRegexp(other)" })
        public DynamicObject initialize(DynamicObject regexp, DynamicObject other) {
            Layouts.REGEXP.setRegex(regexp, Layouts.REGEXP.getRegex(other));
            Layouts.REGEXP.setSource(regexp, Layouts.REGEXP.getSource(other));
            Layouts.REGEXP.setOptions(regexp, Layouts.REGEXP.getOptions(other));
            Layouts.REGEXP.setCachedEncodings(regexp, Layouts.REGEXP.getCachedEncodings(other));
            return regexp;
        }
    }

    @Primitive(name = "regexp_options")
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpOptionsPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "isInitialized(regexp)")
        public int options(DynamicObject regexp) {
            return Layouts.REGEXP.getOptions(regexp).toOptions();
        }

        @Specialization(guards = "!isInitialized(regexp)")
        public int optionsNotInitialized(DynamicObject regexp) {
            throw new RaiseException(coreExceptions().typeError("uninitialized Regexp", this));
        }

    }

    @Primitive(name = "regexp_search_region", lowerFixnum = { 2, 3 })
    @ImportStatic(RegexpGuards.class)
    public static abstract class RegexpSearchRegionPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = { "!isInitialized(regexp)", "isRubyString(string)" })
        public Object searchRegionNotInitialized(DynamicObject regexp, DynamicObject string, int start, int end, boolean forward) {
            throw new RaiseException(coreExceptions().typeError("uninitialized Regexp", this));
        }

        @Specialization(guards = { "isRubyString(string)", "!isValidEncoding(string)" })
        public Object searchRegionInvalidEncoding(DynamicObject regexp, DynamicObject string, int start, int end, boolean forward) {
            throw new RaiseException(coreExceptions().argumentError(formatError(string), this));
        }

        @TruffleBoundary
        private String formatError(DynamicObject string) {
            return StringUtils.format("invalid byte sequence in %s", Layouts.STRING.getRope(string).getEncoding());
        }

        @Specialization(guards = { "isInitialized(regexp)", "isRubyString(string)", "isValidEncoding(string)" })
        public Object searchRegion(VirtualFrame frame, DynamicObject regexp, DynamicObject string, int start, int end, boolean forward,
                @Cached("create()") RopeNodes.BytesNode bytesNode,
                @Cached("create()") MatchNode matchNode) {
            final Rope rope = StringOperations.rope(string);
            final Matcher matcher = RegexpNodes.createMatcher(getContext(), regexp, rope, bytesNode.execute(rope), true);

            if (forward) {
                // Search forward through the string.
                return matchNode.execute(frame, regexp, string, matcher, start, end, false);
            } else {
                // Search backward through the string.
                return matchNode.execute(frame, regexp, string, matcher, end, start, false);
            }
        }

    }
}

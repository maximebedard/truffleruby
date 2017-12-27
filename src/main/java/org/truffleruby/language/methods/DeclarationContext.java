/*
 * Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.language.methods;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.RubyContext;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.objects.SingletonClassNode;

import java.util.Collections;
import java.util.Map;

/**
 * The set of values captured when a method is defined:
 * <ul>
 * <li>the visibility of the method</li>
 * <li>the current module (default definee) to define the method on</li>
 * <li>the currently activated refinement modules which apply to the method</li>
 * </ul>
 */
public class DeclarationContext {

    /** @see <a href="http://yugui.jp/articles/846">http://yugui.jp/articles/846</a> */
    private enum DefaultDefinee {
        /** The closest literal module/class definition is the default definee. */
        LEXICAL_SCOPE,
        /** #instance_eval, the default definee is self.singleton_class */
        SINGLETON_CLASS,
        /** #class_eval, the default definee is self */
        SELF
    }

    public final Visibility visibility;
    public final DefaultDefinee defaultDefinee;
    private final Map<DynamicObject, DynamicObject> refinements; // immutable

    public DeclarationContext(Visibility visibility, DefaultDefinee defaultDefinee) {
        this(visibility, defaultDefinee, Collections.emptyMap());
    }

    private DeclarationContext(Visibility visibility, DefaultDefinee defaultDefinee, Map<DynamicObject, DynamicObject> refinements) {
        this.visibility = visibility;
        this.defaultDefinee = defaultDefinee;
        this.refinements = refinements;
    }

    @TruffleBoundary
    private static Frame lookupVisibility(Frame frame) {
        while (frame != null) {
            final Visibility visibility = RubyArguments.getDeclarationContext(frame).visibility;
            if (visibility != null) {
                return frame;
            }
            frame = RubyArguments.getDeclarationFrame(frame);
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new UnsupportedOperationException("No declaration frame with visibility found");
    }

    @TruffleBoundary
    public static Visibility findVisibility(Frame frame) {
        final Frame visibilityFrame = lookupVisibility(frame);
        return RubyArguments.getDeclarationContext(visibilityFrame).visibility;
    }

    @TruffleBoundary
    private static void changeVisibility(Frame frame, Visibility newVisibility) {
        final Frame visibilityFrame = lookupVisibility(frame);
        final DeclarationContext oldDeclarationContext = RubyArguments.getDeclarationContext(visibilityFrame);
        if (newVisibility != oldDeclarationContext.visibility) {
            RubyArguments.setDeclarationContext(visibilityFrame, oldDeclarationContext.withVisibility(newVisibility));
        }
    }

    @TruffleBoundary
    public static void setCurrentVisibility(RubyContext context, Visibility visibility) {
        final Frame callerFrame = context.getCallStack().getCallerFrameIgnoringSend().getFrame(FrameAccess.READ_WRITE);
        changeVisibility(callerFrame, visibility);
    }

    @TruffleBoundary
    public static void setRefinements(Frame callerFrame, DeclarationContext declarationContext, Map<DynamicObject, DynamicObject> refinements) {
        RubyArguments.setDeclarationContext(callerFrame, declarationContext.withRefinements(refinements));
    }

    private DeclarationContext withVisibility(Visibility visibility) {
        assert visibility != null;
        return new DeclarationContext(visibility, defaultDefinee, refinements);
    }

    public DeclarationContext withDefaultDefinee(DefaultDefinee newDefaultDefinee) {
        assert newDefaultDefinee != null;
        return new DeclarationContext(visibility, newDefaultDefinee, refinements);
    }

    public DeclarationContext withRefinements(Map<DynamicObject, DynamicObject> refinements) {
        assert refinements != null;
        return new DeclarationContext(visibility, defaultDefinee, refinements);
    }

    public Map<DynamicObject, DynamicObject> getRefinements() {
        return refinements;
    }

    public DynamicObject getRefinement(DynamicObject module) {
        return refinements.get(module);
    }

    public DeclarationContext newModuleDeclarationContext() {
        return new DeclarationContext(Visibility.PUBLIC, DefaultDefinee.LEXICAL_SCOPE, refinements);
    }

    @TruffleBoundary
    public DynamicObject getModuleToDefineMethods(Object self, InternalMethod method, RubyContext context, SingletonClassNode singletonClassNode) {
        switch (defaultDefinee) {
        case LEXICAL_SCOPE:
            return method.getSharedMethodInfo().getLexicalScope().getLiveModule();
        case SINGLETON_CLASS:
            return singletonClassNode.executeSingletonClass(self);
        case SELF:
            return (DynamicObject) self;
        default:
            throw new UnsupportedOperationException();
        }
    }

    public static final DeclarationContext MODULE = new DeclarationContext(Visibility.PUBLIC, DefaultDefinee.LEXICAL_SCOPE);
    public static final DeclarationContext METHOD = new DeclarationContext(Visibility.PUBLIC, DefaultDefinee.LEXICAL_SCOPE);
    public static final DeclarationContext BLOCK = new DeclarationContext(null, DefaultDefinee.LEXICAL_SCOPE);
    public static final DeclarationContext TOP_LEVEL = new DeclarationContext(Visibility.PRIVATE, DefaultDefinee.LEXICAL_SCOPE);
    public static final DeclarationContext INSTANCE_EVAL = new DeclarationContext(Visibility.PUBLIC, DefaultDefinee.SINGLETON_CLASS);
    public static final DeclarationContext CLASS_EVAL = new DeclarationContext(Visibility.PUBLIC, DefaultDefinee.SELF);

}

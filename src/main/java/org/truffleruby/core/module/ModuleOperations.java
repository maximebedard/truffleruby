/*
 * Copyright (c) 2014, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.module;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyConstant;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.objects.shared.SharedObjects;
import org.truffleruby.parser.Identifiers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

public abstract class ModuleOperations {

    public static boolean includesModule(DynamicObject module, DynamicObject other) {
        CompilerAsserts.neverPartOfCompilation();
        assert RubyGuards.isRubyModule(module);
        //assert RubyGuards.isRubyModule(other);

        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            if (ancestor == other) {
                return true;
            }
        }

        return false;
    }

    public static boolean assignableTo(DynamicObject thisClass, DynamicObject otherClass) {
        return includesModule(thisClass, otherClass);
    }

    public static boolean canBindMethodTo(DynamicObject origin, DynamicObject module) {
        assert RubyGuards.isRubyModule(origin);
        assert RubyGuards.isRubyModule(module);

        if (!(RubyGuards.isRubyClass(origin))) {
            return true;
        } else {
            return ((RubyGuards.isRubyClass(module)) && ModuleOperations.assignableTo(module, origin));
        }
    }

    @TruffleBoundary
    public static Iterable<Entry<String, RubyConstant>> getAllConstants(DynamicObject module) {
        CompilerAsserts.neverPartOfCompilation();

        assert RubyGuards.isRubyModule(module);

        final Map<String, RubyConstant> constants = new HashMap<>();

        // Look in the current module
        for (Map.Entry<String, RubyConstant> constant : Layouts.MODULE.getFields(module).getConstants()) {
            constants.put(constant.getKey(), constant.getValue());
        }

        // Look in ancestors
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).prependedAndIncludedModules()) {
            for (Map.Entry<String, RubyConstant> constant : Layouts.MODULE.getFields(ancestor).getConstants()) {
                if (!constants.containsKey(constant.getKey())) {
                    constants.put(constant.getKey(), constant.getValue());
                }
            }
        }

        return constants.entrySet();
    }

    @TruffleBoundary
    public static ConstantLookupResult lookupConstant(RubyContext context, DynamicObject module, String name) {
        return lookupConstant(context, module, name, new ArrayList<>());
    }

    private static ConstantLookupResult lookupConstant(RubyContext context, DynamicObject module, String name, ArrayList<Assumption> assumptions) {
        CompilerAsserts.neverPartOfCompilation();
        assert RubyGuards.isRubyModule(module);

        // Look in the current module
        ModuleFields fields = Layouts.MODULE.getFields(module);
        assumptions.add(fields.getConstantsUnmodifiedAssumption());
        RubyConstant constant = fields.getConstant(name);
        if (constant != null) {
            return new ConstantLookupResult(constant, toArray(assumptions));
        }

        // Look in ancestors
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).parentAncestors()) {
            fields = Layouts.MODULE.getFields(ancestor);
            assumptions.add(fields.getConstantsUnmodifiedAssumption());
            constant = fields.getConstant(name);
            if (constant != null) {
                return new ConstantLookupResult(constant, toArray(assumptions));
            }
        }

        // Nothing found
        return new ConstantLookupResult(null, toArray(assumptions));
    }

    private static ConstantLookupResult lookupConstantInObject(RubyContext context, DynamicObject module, String name, ArrayList<Assumption> assumptions) {
        // Look in Object and its included modules for modules (not for classes)
        if (!RubyGuards.isRubyClass(module)) {
            final DynamicObject objectClass = context.getCoreLibrary().getObjectClass();

            ModuleFields fields = Layouts.MODULE.getFields(objectClass);
            assumptions.add(fields.getConstantsUnmodifiedAssumption());
            RubyConstant constant = fields.getConstant(name);
            if (constant != null) {
                return new ConstantLookupResult(constant, toArray(assumptions));
            }


            for (DynamicObject ancestor : Layouts.MODULE.getFields(objectClass).prependedAndIncludedModules()) {
                fields = Layouts.MODULE.getFields(ancestor);
                assumptions.add(fields.getConstantsUnmodifiedAssumption());
                constant = fields.getConstant(name);
                if (constant != null) {
                    return new ConstantLookupResult(constant, toArray(assumptions));
                }
            }
        }

        return new ConstantLookupResult(null, toArray(assumptions));
    }

    public static ConstantLookupResult lookupConstantAndObject(RubyContext context, DynamicObject module, String name, ArrayList<Assumption> assumptions) {
        final ConstantLookupResult constant = lookupConstant(context, module, name, assumptions);
        if (constant.isFound()) {
            return constant;
        }

        return lookupConstantInObject(context, module, name, assumptions);
    }

    @TruffleBoundary
    public static ConstantLookupResult lookupConstantWithLexicalScope(RubyContext context, LexicalScope lexicalScope, String name) {
        final DynamicObject module = lexicalScope.getLiveModule();
        final ArrayList<Assumption> assumptions = new ArrayList<>();

        // Look in lexical scope
        while (lexicalScope != context.getRootLexicalScope()) {
            ModuleFields fields = Layouts.MODULE.getFields(lexicalScope.getLiveModule());
            assumptions.add(fields.getConstantsUnmodifiedAssumption());
            RubyConstant constant = fields.getConstant(name);
            if (constant != null) {
                return new ConstantLookupResult(constant, toArray(assumptions));
            }

            lexicalScope = lexicalScope.getParent();
        }

        return lookupConstantAndObject(context, module, name, assumptions);
    }

    public static ConstantLookupResult lookupScopedConstant(RubyContext context, DynamicObject module, String fullName, boolean inherit, Node currentNode) {
        CompilerAsserts.neverPartOfCompilation();

        int start = 0, next;
        if (fullName.startsWith("::")) {
            module = context.getCoreLibrary().getObjectClass();
            start += 2;
        }

        while ((next = fullName.indexOf("::", start)) != -1) {
            final String segment = fullName.substring(start, next);
            final ConstantLookupResult constant = lookupConstantWithInherit(context, module, segment, inherit, currentNode);
            if (!constant.isFound()) {
                return constant;
            } else if (RubyGuards.isRubyModule(constant.getConstant().getValue())) {
                module = (DynamicObject) constant.getConstant().getValue();
            } else {
                throw new RaiseException(context.getCoreExceptions().typeError(fullName.substring(0, next) + " does not refer to class/module", currentNode));
            }
            start = next + 2;
        }

        final String lastSegment = fullName.substring(start);
        if (!Identifiers.isValidConstantName19(lastSegment)) {
            throw new RaiseException(context.getCoreExceptions().nameError(StringUtils.format("wrong constant name %s", fullName), module, fullName, currentNode));
        }

        return lookupConstantWithInherit(context, module, lastSegment, inherit, currentNode);
    }

    @TruffleBoundary(transferToInterpreterOnException = false)
    public static ConstantLookupResult lookupConstantWithInherit(RubyContext context, DynamicObject module, String name, boolean inherit, Node currentNode) {
        assert RubyGuards.isRubyModule(module);

        if (!Identifiers.isValidConstantName19(name)) {
            throw new RaiseException(context.getCoreExceptions().nameError(StringUtils.format("wrong constant name %s", name), module, name, currentNode));
        }

        if (inherit) {
            return ModuleOperations.lookupConstantAndObject(context, module, name, new ArrayList<>());
        } else {
            final ModuleFields fields = Layouts.MODULE.getFields(module);
            final RubyConstant constant = fields.getConstant(name);
            return new ConstantLookupResult(constant, fields.getConstantsUnmodifiedAssumption());
        }
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> getAllMethods(DynamicObject module) {
        assert RubyGuards.isRubyModule(module);

        final Map<String, InternalMethod> methods = new HashMap<>();

        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            for (InternalMethod method : Layouts.MODULE.getFields(ancestor).getMethods()) {
                if (!methods.containsKey(method.getName())) {
                    methods.put(method.getName(), method);
                }
            }
        }

        return methods;
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> getMethodsBeforeLogicalClass(DynamicObject module) {
        assert RubyGuards.isRubyModule(module);

        final Map<String, InternalMethod> methods = new HashMap<>();

        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            // When we find a class which is not a singleton class, we are done
            if (RubyGuards.isRubyClass(ancestor) && !Layouts.CLASS.getIsSingleton(ancestor)) {
                break;
            }

            for (InternalMethod method : Layouts.MODULE.getFields(ancestor).getMethods()) {
                if (!methods.containsKey(method.getName())) {
                    methods.put(method.getName(), method);
                }
            }
        }

        return methods;
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> getMethodsUntilLogicalClass(DynamicObject module) {
        assert RubyGuards.isRubyModule(module);

        final Map<String, InternalMethod> methods = new HashMap<>();

        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            for (InternalMethod method : Layouts.MODULE.getFields(ancestor).getMethods()) {
                if (!methods.containsKey(method.getName())) {
                    methods.put(method.getName(), method);
                }
            }

            // When we find a class which is not a singleton class, we are done
            if (RubyGuards.isRubyClass(ancestor) && !Layouts.CLASS.getIsSingleton(ancestor)) {
                break;
            }
        }

        return methods;
    }

    @TruffleBoundary
    public static Map<String, InternalMethod> withoutUndefinedMethods(Map<String, InternalMethod> methods) {
        Map<String, InternalMethod> definedMethods = new HashMap<>();
        for (Entry<String, InternalMethod> method : methods.entrySet()) {
            if (!method.getValue().isUndefined()) {
                definedMethods.put(method.getKey(), method.getValue());
            }
        }
        return definedMethods;
    }

    @TruffleBoundary
    public static MethodLookupResult lookupMethodCachedWithRefinements(DynamicObject module, String name, LexicalScope lexicalScope) {
        final ArrayList<Assumption> assumptions = new ArrayList<>();

        // Look in ancestors
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            ModuleFields fields = Layouts.MODULE.getFields(ancestor);
            assumptions.add(fields.getMethodsUnmodifiedAssumption());
            InternalMethod method = fields.getMethod(name);
            if (method != null) {
                if (method.isRefined() &&
                        lexicalScope != null &&
                        !lexicalScope.getRefinements().isEmpty() &&
                        lexicalScope.getRefinements().containsKey(module)) {
                    // TODO BJF Need to pass assumptions here?
                    return lookupMethodWithRefinements(lexicalScope.getRefinements().get(module), name, null);
                }
                return new MethodLookupResult(method, toArray(assumptions));
            }
        }

        // Nothing found
        return new MethodLookupResult(null, toArray(assumptions));
    }

    @TruffleBoundary
    public static InternalMethod lookupMethodUncached(DynamicObject module, String name) {
        // Look in ancestors
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            final ModuleFields fields = Layouts.MODULE.getFields(ancestor);
            final InternalMethod method = fields.getMethod(name);
            if (method != null) {
                return method;
            }
        }

        // Nothing found
        return null;
    }

    public static InternalMethod lookupMethod(DynamicObject module, String name, Visibility visibility) {
        final InternalMethod method = lookupMethodUncachedWithRefinements(module, name, null);
        if (method == null || method.isUndefined()) {
            return null;
        }
        return method.getVisibility() == visibility ? method : null;
    }

    public static MethodLookupResult lookupSuperMethod(InternalMethod currentMethod, DynamicObject objectMetaClass) {
        assert RubyGuards.isRubyModule(objectMetaClass);
        final String name = currentMethod.getSharedMethodInfo().getName(); // use the original name
        return lookupSuperMethod(currentMethod.getDeclaringModule(), name, objectMetaClass);
    }

    @TruffleBoundary
    private static MethodLookupResult lookupSuperMethod(DynamicObject declaringModule, String name, DynamicObject objectMetaClass) {
        assert RubyGuards.isRubyModule(declaringModule);
        assert RubyGuards.isRubyModule(objectMetaClass);

        final ArrayList<Assumption> assumptions = new ArrayList<>();
        boolean foundDeclaringModule = false;
        for (DynamicObject module : Layouts.MODULE.getFields(objectMetaClass).ancestors()) {
            if (module == declaringModule) {
                foundDeclaringModule = true;
            } else if (foundDeclaringModule) {
                ModuleFields fields = Layouts.MODULE.getFields(module);
                assumptions.add(fields.getMethodsUnmodifiedAssumption());
                InternalMethod method = fields.getMethod(name);
                if (method != null) {
                    return new MethodLookupResult(method, toArray(assumptions));
                }
            }
        }

        return new MethodLookupResult(null, toArray(assumptions));
    }

    private static Assumption[] toArray(ArrayList<Assumption> assumptions) {
        return assumptions.toArray(new Assumption[assumptions.size()]);
    }

    @TruffleBoundary
    public static Map<String, Object> getAllClassVariables(DynamicObject module) {
        CompilerAsserts.neverPartOfCompilation();

        assert RubyGuards.isRubyModule(module);

        final Map<String, Object> classVariables = new HashMap<>();

        classVariableLookup(module, module1 -> {
            classVariables.putAll(Layouts.MODULE.getFields(module1).getClassVariables());
            return null;
        });

        return classVariables;
    }

    @TruffleBoundary
    public static Object lookupClassVariable(DynamicObject module, final String name) {
        assert RubyGuards.isRubyModule(module);

        return classVariableLookup(module, module1 -> Layouts.MODULE.getFields(module1).getClassVariables().get(name));
    }

    @TruffleBoundary(transferToInterpreterOnException = false)
    public static void setClassVariable(final RubyContext context, DynamicObject module, final String name, final Object value, final Node currentNode) {
        assert RubyGuards.isRubyModule(module);
        ModuleFields moduleFields = Layouts.MODULE.getFields(module);
        moduleFields.checkFrozen(context, currentNode);
        SharedObjects.propagate(context, module, value);

        // if the cvar is not already defined we need to take lock and ensure there is only one
        // defined in the class tree
        if (!trySetClassVariable(module, name, value)) {
            synchronized (context.getClassVariableDefinitionLock()) {
                if (!trySetClassVariable(module, name, value)) {
                    moduleFields.getClassVariables().put(name, value);
                }
            }
        }
    }

    private static boolean trySetClassVariable(DynamicObject topModule, final String name, final Object value) {
        final DynamicObject found = classVariableLookup(topModule, module -> {
            final ModuleFields moduleFields = Layouts.MODULE.getFields(module);
            if (moduleFields.getClassVariables().replace(name, value) != null) {
                return module;
            } else {
                return null;
            }
        });
        return found != null;
    }

    @TruffleBoundary(transferToInterpreterOnException = false)
    public static Object removeClassVariable(ModuleFields moduleFields, RubyContext context, Node currentNode, String name) {
        moduleFields.checkFrozen(context, currentNode);

        final Object found = moduleFields.getClassVariables().remove(name);
        if (found == null) {
            throw new RaiseException(context.getCoreExceptions().nameErrorClassVariableNotDefined(name, moduleFields.rubyModuleObject, currentNode));
        }
        return found;
    }

    private static <R> R classVariableLookup(DynamicObject module, Function<DynamicObject, R> action) {
        CompilerAsserts.neverPartOfCompilation();

        // Look in the current module
        R result = action.apply(module);
        if (result != null) {
            return result;
        }

        // If singleton class of a module, check the attached module.
        if (RubyGuards.isRubyClass(module)) {
            DynamicObject klass = module;
            if (Layouts.CLASS.getIsSingleton(klass) && Layouts.MODULE.isModule(Layouts.CLASS.getAttached(klass))) {
                module = Layouts.CLASS.getAttached(klass);

                result = action.apply(module);
                if (result != null) {
                    return result;
                }
            }
        }

        // Look in ancestors
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).parentAncestors()) {
            result = action.apply(ancestor);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    public static boolean isMethodPrivateFromName(String name) {
        CompilerAsserts.neverPartOfCompilation();

        return (name.equals("initialize") || name.equals("initialize_copy") ||
                name.equals("initialize_clone") || name.equals("initialize_dup") ||
                name.equals("respond_to_missing?"));
    }

}

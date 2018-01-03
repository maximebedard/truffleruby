/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.module;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.utilities.CyclicAssumption;

import org.truffleruby.Layouts;
import org.truffleruby.Log;
import org.truffleruby.RubyContext;
import org.truffleruby.core.klass.ClassNodes;
import org.truffleruby.core.method.MethodFilter;
import org.truffleruby.language.RubyConstant;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.objects.IsFrozenNode;
import org.truffleruby.language.objects.ObjectGraphNode;
import org.truffleruby.language.objects.ObjectIDOperations;
import org.truffleruby.language.objects.shared.SharedObjects;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ModuleFields implements ModuleChain, ObjectGraphNode {

    public static void debugModuleChain(DynamicObject module) {
        assert RubyGuards.isRubyModule(module);
        ModuleChain chain = Layouts.MODULE.getFields(module);
        final StringBuilder builder = new StringBuilder();
        while (chain != null) {
            builder.append(chain.getClass());
            if (!(chain instanceof PrependMarker)) {
                DynamicObject real = chain.getActualModule();
                builder.append(" " + Layouts.MODULE.getFields(real).getName());
            }
            builder.append(System.lineSeparator());
            chain = chain.getParentModule();
        }
        Log.LOGGER.info(builder.toString());
    }

    public DynamicObject rubyModuleObject;

    // The context is stored here - objects can obtain it via their class (which is a module)
    private final RubyContext context;

    private final SourceSection sourceSection;

    public final PrependMarker start;
    @CompilationFinal public ModuleChain parentModule;

    public final DynamicObject lexicalParent;
    public final String givenBaseName;

    private boolean hasFullName = false;
    private String name = null;

    private boolean isRefinement = false;
    private boolean isOverlaid = false;
    private DynamicObject refinedClass;
    private DynamicObject definedAt;

    private final ConcurrentMap<String, InternalMethod> methods = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RubyConstant> constants = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> classVariables = new ConcurrentHashMap<>();

    // Maps a refined class to refinement module
    private final ConcurrentMap<DynamicObject, DynamicObject> refinements = new ConcurrentHashMap<>();

    // Maps a refined class to activated refinement module
    private final ConcurrentMap<DynamicObject, DynamicObject> activatedRefinements = new ConcurrentHashMap<>();

    private final CyclicAssumption methodsUnmodifiedAssumption;
    private final CyclicAssumption constantsUnmodifiedAssumption;

    // Concurrency: only modified during boot
    private final Map<String, Assumption> inlinedBuiltinsAssumptions = new HashMap<>();

    public ModuleFields(RubyContext context, SourceSection sourceSection, DynamicObject lexicalParent, String givenBaseName) {
        assert lexicalParent == null || RubyGuards.isRubyModule(lexicalParent);
        this.context = context;
        this.sourceSection = sourceSection;
        this.lexicalParent = lexicalParent;
        this.givenBaseName = givenBaseName;
        this.methodsUnmodifiedAssumption = new CyclicAssumption(String.valueOf(givenBaseName) + " methods are unmodified");
        this.constantsUnmodifiedAssumption = new CyclicAssumption(String.valueOf(givenBaseName) + " constants are unmodified");
        start = new PrependMarker(this);
    }

    public RubyConstant getAdoptedByLexicalParent(
            RubyContext context,
            DynamicObject lexicalParent,
            String name,
            Node currentNode) {
        assert RubyGuards.isRubyModule(lexicalParent);

        RubyConstant previous = Layouts.MODULE.getFields(lexicalParent).setConstantInternal(
                context,
                currentNode,
                name,
                rubyModuleObject,
                false);

        if (!hasFullName()) {
            // Tricky, we need to compare with the Object class, but we only have a Class at hand.
            final DynamicObject classClass = Layouts.BASIC_OBJECT.getLogicalClass(getLogicalClass());
            final DynamicObject objectClass = ClassNodes.getSuperClass(ClassNodes.getSuperClass(classClass));

            if (lexicalParent == objectClass) {
                this.setFullName(name);
                updateAnonymousChildrenModules(context);
            } else if (Layouts.MODULE.getFields(lexicalParent).hasFullName()) {
                this.setFullName(Layouts.MODULE.getFields(lexicalParent).getName() + "::" + name);
                updateAnonymousChildrenModules(context);
            }
            // else: Our lexicalParent is also an anonymous module
            // and will name us when it gets named via updateAnonymousChildrenModules()
        }
        return previous;
    }

    public void updateAnonymousChildrenModules(RubyContext context) {
        for (Map.Entry<String, RubyConstant> entry : constants.entrySet()) {
            RubyConstant constant = entry.getValue();
            if (RubyGuards.isRubyModule(constant.getValue())) {
                DynamicObject module = (DynamicObject) constant.getValue();
                if (!Layouts.MODULE.getFields(module).hasFullName()) {
                    Layouts.MODULE.getFields(module).getAdoptedByLexicalParent(
                            context,
                            rubyModuleObject,
                            entry.getKey(),
                            null);
                }
            }
        }
    }

    private boolean hasPrependedModules() {
        return start.getParentModule() != this;
    }

    public ModuleChain getFirstModuleChain() {
        return start.getParentModule();
    }

    @TruffleBoundary
    public void initCopy(DynamicObject from) {
        assert RubyGuards.isRubyModule(from);

        // Do not copy name, the copy is an anonymous module
        final ModuleFields fromFields = Layouts.MODULE.getFields(from);

        for (InternalMethod method : fromFields.methods.values()) {
            this.methods.put(method.getName(), method.withDeclaringModule(rubyModuleObject));
        }

        for (Entry<String, RubyConstant> entry : fromFields.constants.entrySet()) {
            this.constants.put(entry.getKey(), entry.getValue());
        }

        this.classVariables.putAll(fromFields.classVariables);

        if (fromFields.hasPrependedModules()) {
            // Then the parent is the first in the prepend chain
            this.parentModule = fromFields.start.getParentModule();
        } else {
            this.parentModule = fromFields.parentModule;
        }

        if (Layouts.CLASS.isClass(rubyModuleObject)) {
            // Singleton classes cannot be instantiated
            if (!Layouts.CLASS.getIsSingleton(from)) {
                ClassNodes.setInstanceFactory(rubyModuleObject, from);
            }

            Layouts.CLASS.setSuperclass(rubyModuleObject, Layouts.CLASS.getSuperclass(from));
        }
    }

    // TODO (eregon, 12 May 2015): ideally all callers would be nodes and check themselves.
    public void checkFrozen(RubyContext context, Node currentNode) {
        if (context.getCoreLibrary() != null && IsFrozenNode.isFrozen(rubyModuleObject)) {
            throw new RaiseException(context.getCoreExceptions().frozenError(rubyModuleObject, currentNode));
        }
    }

    public void insertAfter(DynamicObject module) {
        parentModule = new IncludedModule(module, parentModule);
    }

    @TruffleBoundary
    public void include(RubyContext context, Node currentNode, DynamicObject module) {
        assert RubyGuards.isRubyModule(module);

        checkFrozen(context, currentNode);

        // If the module we want to include already includes us, it is cyclic
        if (ModuleOperations.includesModule(module, rubyModuleObject)) {
            throw new RaiseException(context.getCoreExceptions().argumentError("cyclic include detected", currentNode));
        }

        includeModule(context, module);
    }

    @TruffleBoundary
    public DynamicObject includeModule(RubyContext context, DynamicObject module) {
        SharedObjects.propagate(context, rubyModuleObject, module);

        // We need to include the module ancestors in reverse order for a given inclusionPoint
        ModuleChain inclusionPoint = this;
        Deque<DynamicObject> modulesToInclude = new ArrayDeque<>();
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            if (ModuleOperations.includesModule(rubyModuleObject, ancestor)) {
                if (isIncludedModuleBeforeSuperClass(ancestor)) {
                    // Include the modules at the appropriate inclusionPoint
                    performIncludes(inclusionPoint, modulesToInclude);
                    assert modulesToInclude.isEmpty();

                    // We need to include the others after that module
                    inclusionPoint = parentModule;
                    while (inclusionPoint.getActualModule() != ancestor) {
                        inclusionPoint = inclusionPoint.getParentModule();
                    }
                } else {
                    // Just ignore this module, as it is included above the superclass
                }
            } else {
                modulesToInclude.push(ancestor);
            }
        }

        performIncludes(inclusionPoint, modulesToInclude);

        newHierarchyVersion();

        if (this.getParentModule() instanceof IncludedModule) {
            return ((IncludedModule) this.getParentModule()).getActualModule();
        } else if (this.getParentModule() instanceof DynamicObject) {
            return (DynamicObject) this.getParentModule();
        } else {
            return null;
        }
    }

    public void performIncludes(ModuleChain inclusionPoint, Deque<DynamicObject> moduleAncestors) {
        while (!moduleAncestors.isEmpty()) {
            DynamicObject mod = moduleAncestors.pop();
            assert RubyGuards.isRubyModule(mod);
            inclusionPoint.insertAfter(mod);
        }
    }

    public boolean isIncludedModuleBeforeSuperClass(DynamicObject module) {
        assert RubyGuards.isRubyModule(module);
        ModuleChain included = parentModule;
        while (included instanceof IncludedModule) {
            if (included.getActualModule() == module) {
                return true;
            }
            included = included.getParentModule();
        }
        return false;
    }

    @TruffleBoundary
    public void prepend(RubyContext context, Node currentNode, DynamicObject module) {
        assert RubyGuards.isRubyModule(module);

        checkFrozen(context, currentNode);

        // If the module we want to prepend already includes us, it is cyclic
        if (ModuleOperations.includesModule(module, rubyModuleObject)) {
            throw new RaiseException(context.getCoreExceptions().argumentError("cyclic prepend detected", currentNode));
        }

        SharedObjects.propagate(context, rubyModuleObject, module);

        ModuleChain mod = Layouts.MODULE.getFields(module).start;
        final ModuleChain topPrependedModule = start.getParentModule();
        ModuleChain cur = start;
        while (mod != null && !(mod instanceof ModuleFields && RubyGuards.isRubyClass(((ModuleFields) mod).rubyModuleObject))) {
            if (!(mod instanceof PrependMarker)) {
                if (!ModuleOperations.includesModule(rubyModuleObject, mod.getActualModule())) {
                    cur.insertAfter(mod.getActualModule());
                    cur = cur.getParentModule();
                }
            }
            mod = mod.getParentModule();
        }

        // If there were already prepended modules, invalidate the first of them
        if (topPrependedModule != this) {
            Layouts.MODULE.getFields(topPrependedModule.getActualModule()).newHierarchyVersion();
        } else {
            this.newHierarchyVersion();
        }

        prependInvalidation();
    }

    /**
     * Set the value of a constant, possibly redefining it.
     */
    @TruffleBoundary
    public RubyConstant setConstant(RubyContext context, Node currentNode, String name, Object value) {
        if (RubyGuards.isRubyModule(value)) {
            return Layouts.MODULE.getFields((DynamicObject) value).getAdoptedByLexicalParent(context, rubyModuleObject, name, currentNode);
        } else {
            return setConstantInternal(context, currentNode, name, value, false);
        }
    }

    @TruffleBoundary
    public void setAutoloadConstant(RubyContext context, Node currentNode, String name, DynamicObject filename) {
        assert RubyGuards.isRubyString(filename);
        setConstantInternal(context, currentNode, name, filename, true);
    }

    public RubyConstant setConstantInternal(RubyContext context, Node currentNode, String name, Object value, boolean autoload) {
        checkFrozen(context, currentNode);

        SharedObjects.propagate(context, rubyModuleObject, value);

        while (true) {
            final RubyConstant previous = constants.get(name);
            final boolean isPrivate = previous != null && previous.isPrivate();
            final boolean isDeprecated = previous != null && previous.isDeprecated();
            final SourceSection sourceSection = currentNode != null ?  currentNode.getSourceSection() : null;
            final RubyConstant newValue = new RubyConstant(rubyModuleObject, value, isPrivate, autoload, isDeprecated, sourceSection);

            if ((previous == null) ? (constants.putIfAbsent(name, newValue) == null) : constants.replace(name, previous, newValue)) {
                newConstantsVersion();
                if (!autoload && previous != null && !previous.isAutoload()) {
                    return previous;
                } else {
                    return null;
                }
            }
        }
    }

    @TruffleBoundary
    public RubyConstant removeConstant(RubyContext context, Node currentNode, String name) {
        checkFrozen(context, currentNode);
        final RubyConstant oldConstant = constants.remove(name);
        newConstantsVersion();
        return oldConstant;
    }

    @TruffleBoundary
    public void addMethod(RubyContext context, Node currentNode, InternalMethod method) {
        assert ModuleOperations.canBindMethodTo(method.getDeclaringModule(), rubyModuleObject) ||
                ModuleOperations.assignableTo(context.getCoreLibrary().getObjectClass(), method.getDeclaringModule()) ||
                // TODO (pitr-ch 24-Jul-2016): find out why undefined methods sometimes do not match above assertion
                // e.g. "block in _routes route_set.rb:525" in rails/actionpack/lib/action_dispatch/routing/
                (method.isUndefined() && methods.get(method.getName()) != null);

        checkFrozen(context, currentNode);

        if (SharedObjects.isShared(context, rubyModuleObject)) {
            Set<DynamicObject> adjacent = new HashSet<>();
            method.getAdjacentObjects(adjacent);
            for (DynamicObject object : adjacent) {
                SharedObjects.writeBarrier(context, object);
            }
        }

        methods.put(method.getName(), method);

        if (!context.getCoreLibrary().isInitializing()) {
            newMethodsVersion();
            changedMethod(method.getName());
        }

        if (context.getCoreLibrary().isLoaded() && !method.isUndefined()) {
            if (RubyGuards.isSingletonClass(rubyModuleObject)) {
                DynamicObject receiver = Layouts.CLASS.getAttached(rubyModuleObject);
                context.send(
                        receiver,
                        "singleton_method_added",
                        null,
                        context.getSymbolTable().getSymbol(method.getName()));
            } else {
                context.send(
                        rubyModuleObject,
                        "method_added",
                        null,
                        context.getSymbolTable().getSymbol(method.getName()));
            }
        }
    }

    @TruffleBoundary
    public void removeMethod(String methodName) {
        methods.remove(methodName);
        newMethodsVersion();
        changedMethod(methodName);
    }

    @TruffleBoundary
    public void undefMethod(RubyContext context, Node currentNode, String methodName) {
        final InternalMethod method = ModuleOperations.lookupMethodUncached(rubyModuleObject, methodName, null);
        if (method == null || method.isUndefined()) {
            throw new RaiseException(context.getCoreExceptions().nameErrorUndefinedMethod(
                    methodName,
                    rubyModuleObject,
                    currentNode));
        } else {
            addMethod(context, currentNode, method.undefined());
        }
    }

    /**
     * Also searches on Object for modules.
     * Used for alias_method, visibility changes, etc.
     */
    @TruffleBoundary
    public InternalMethod deepMethodSearch(RubyContext context, String name) {
        InternalMethod method = ModuleOperations.lookupMethodUncached(rubyModuleObject, name, null);
        if (method != null && !method.isUndefined()) {
            return method;
        }

        // Also search on Object if we are a Module. JRuby calls it deepMethodSearch().
        if (!RubyGuards.isRubyClass(rubyModuleObject)) { // TODO: handle undefined methods
            method = ModuleOperations.lookupMethodUncached(context.getCoreLibrary().getObjectClass(), name, null);

            if (method != null && !method.isUndefined()) {
                return method;
            }
        }

        return null;
    }

    @TruffleBoundary(transferToInterpreterOnException = false)
    public void alias(RubyContext context, Node currentNode, String newName, String oldName) {
        InternalMethod method = deepMethodSearch(context, oldName);

        if (method == null) {
            throw new RaiseException(context.getCoreExceptions().nameErrorUndefinedMethod(
                    oldName,
                    rubyModuleObject,
                    currentNode));
        }

        InternalMethod aliasMethod = method.withName(newName);

        if (ModuleOperations.isMethodPrivateFromName(newName)) {
            aliasMethod = aliasMethod.withVisibility(Visibility.PRIVATE);
        }

        addMethod(context, currentNode, aliasMethod);
    }

    @TruffleBoundary
    public void changeConstantVisibility(
            final RubyContext context,
            final Node currentNode,
            final String name,
            final boolean isPrivate) {

        while (true) {
            final RubyConstant previous = constants.get(name);

            if (previous == null) {
                throw new RaiseException(context.getCoreExceptions().nameErrorUninitializedConstant(rubyModuleObject, name, currentNode));
            }

            if (constants.replace(name, previous, previous.withPrivate(isPrivate))) {
                newConstantsVersion();
                break;
            }
        }
    }

    @TruffleBoundary
    public void deprecateConstant(RubyContext context, Node currentNode, String name) {
        while (true) {
            final RubyConstant previous = constants.get(name);

            if (previous == null) {
                throw new RaiseException(context.getCoreExceptions().nameErrorUninitializedConstant(rubyModuleObject, name, currentNode));
            }

            if (constants.replace(name, previous, previous.withDeprecated())) {
                newConstantsVersion();
                break;
            }
        }
    }

    public RubyContext getContext() {
        return context;
    }

    public String getName() {
        final String name = this.name;
        if (name == null) {
            // Lazily compute the anonymous name because it is expensive
            CompilerDirectives.transferToInterpreterAndInvalidate();
            final String anonymousName = createAnonymousName();
            this.name = anonymousName;
            return anonymousName;
        }
        return name;
    }

    public void setFullName(String name) {
        assert name != null;
        hasFullName = true;
        this.name = name;
    }

    @TruffleBoundary
    private String createAnonymousName() {
        if (givenBaseName != null) {
            return Layouts.MODULE.getFields(lexicalParent).getName() + "::" + givenBaseName;
        } else if (getLogicalClass() == rubyModuleObject) { // For the case of class Class during initialization
            return "#<cyclic>";
        } else {
            return "#<" + Layouts.MODULE.getFields(getLogicalClass()).getName() + ":0x" + Long.toHexString(ObjectIDOperations.verySlowGetObjectID(context, rubyModuleObject)) + ">";
        }
    }

    public boolean hasFullName() {
        return hasFullName;
    }

    public boolean hasPartialName() {
        return hasFullName() || givenBaseName != null;
    }

    public boolean isRefinement() {
        return isRefinement;
    }

    public void setRefinement(boolean refinement) {
        isRefinement = refinement;
    }

    public boolean isOverlaid() {
        return isOverlaid;
    }

    public void setOverlaid(boolean overlaid) {
        isOverlaid = overlaid;
    }

    public DynamicObject getRefinedClass() {
        return refinedClass;
    }

    public void setRefinedClass(DynamicObject refinedClass) {
        this.refinedClass = refinedClass;
    }

    public DynamicObject getDefinedAt() {
        return definedAt;
    }

    public void setDefinedAt(DynamicObject definedAt) {
        this.definedAt = definedAt;
    }

    @Override
    public String toString() {
        return super.toString() + "(" + getName() + ")";
    }

    private void newConstantsVersion() {
        constantsUnmodifiedAssumption.invalidate();
    }

    public void newHierarchyVersion() {
        newConstantsVersion();
        newMethodsVersion();
    }

    public void newMethodsVersion() {
        methodsUnmodifiedAssumption.invalidate();
    }

    public Assumption getConstantsUnmodifiedAssumption() {
        return constantsUnmodifiedAssumption.getAssumption();
    }

    public Assumption getMethodsUnmodifiedAssumption() {
        return methodsUnmodifiedAssumption.getAssumption();
    }

    public Assumption getHierarchyUnmodifiedAssumption() {
        // Both assumptions are invalidated on hierarchy changes, just pick one of them.
        return getMethodsUnmodifiedAssumption();
    }

    public Iterable<Entry<String, RubyConstant>> getConstants() {
        return constants.entrySet();
    }

    @TruffleBoundary
    public RubyConstant getConstant(String name) {
        return constants.get(name);
    }

    public Iterable<InternalMethod> getMethods() {
        return methods.values();
    }

    @TruffleBoundary
    public InternalMethod getMethod(String name) {
        return methods.get(name);
    }

    public ConcurrentMap<String, Object> getClassVariables() {
        return classVariables;
    }

    public ConcurrentMap<DynamicObject, DynamicObject> getRefinements() {
        return refinements;
    }

    public ConcurrentMap<DynamicObject, DynamicObject> getActivatedRefinements() {
        return activatedRefinements;
    }

    public ModuleChain getParentModule() {
        return parentModule;
    }

    public DynamicObject getActualModule() {
        return rubyModuleObject;
    }

    public Iterable<DynamicObject> ancestors() {
        final ModuleChain top = start;
        return () -> new AncestorIterator(top);
    }

    public Iterable<DynamicObject> parentAncestors() {
        final ModuleChain top = start;
        return () -> {
            final AncestorIterator iterator = new AncestorIterator(top);
            if (iterator.hasNext()) {
                iterator.next();
            }
            return iterator;
        };
    }

    /**
     * Iterates over include'd and prepend'ed modules.
     */
    public Iterable<DynamicObject> prependedAndIncludedModules() {
        final ModuleChain top = start;
        final ModuleFields currentModule = this;
        return () -> new IncludedModulesIterator(top, currentModule);
    }

    public Collection<DynamicObject> filterMethods(RubyContext context, boolean includeAncestors, MethodFilter filter) {
        final Map<String, InternalMethod> allMethods;
        if (includeAncestors) {
            allMethods = ModuleOperations.getAllMethods(rubyModuleObject);
        } else {
            allMethods = methods;
        }
        return filterMethods(context, allMethods, filter);
    }

    public Collection<DynamicObject> filterMethodsOnObject(
            RubyContext context,
            boolean includeAncestors,
            MethodFilter filter) {
        final Map<String, InternalMethod> allMethods;
        if (includeAncestors) {
            allMethods = ModuleOperations.getAllMethods(rubyModuleObject);
        } else {
            allMethods = ModuleOperations.getMethodsUntilLogicalClass(rubyModuleObject);
        }
        return filterMethods(context, allMethods, filter);
    }

    public Collection<DynamicObject> filterSingletonMethods(
            RubyContext context,
            boolean includeAncestors,
            MethodFilter filter) {
        final Map<String, InternalMethod> allMethods;
        if (includeAncestors) {
            allMethods = ModuleOperations.getMethodsBeforeLogicalClass(rubyModuleObject);
        } else {
            allMethods = methods;
        }
        return filterMethods(context, allMethods, filter);
    }

    public Collection<DynamicObject> filterMethods(
            RubyContext context,
            Map<String, InternalMethod> allMethods,
            MethodFilter filter) {
        final Map<String, InternalMethod> methods = ModuleOperations.withoutUndefinedMethods(allMethods);

        final Set<DynamicObject> filtered = new HashSet<>();
        for (InternalMethod method : methods.values()) {
            if (filter.filter(method)) {
                filtered.add(context.getSymbolTable().getSymbol(method.getName()));
            }
        }

        return filtered;
    }

    public DynamicObject getLogicalClass() {
        return Layouts.BASIC_OBJECT.getLogicalClass(rubyModuleObject);
    }

    @Override
    public void getAdjacentObjects(Set<DynamicObject> adjacent) {
        if (lexicalParent != null) {
            adjacent.add(lexicalParent);
        }

        for (DynamicObject module : prependedAndIncludedModules()) {
            adjacent.add(module);
        }

        if (Layouts.CLASS.isClass(rubyModuleObject)) {
            DynamicObject superClass = ClassNodes.getSuperClass(rubyModuleObject);
            if (superClass != null) {
                adjacent.add(superClass);
            }
        }

        for (RubyConstant constant : constants.values()) {
            final Object value = constant.getValue();

            if (value instanceof DynamicObject) {
                adjacent.add((DynamicObject) value);
            }
        }

        for (Object value : classVariables.values()) {
            if (value instanceof DynamicObject) {
                adjacent.add((DynamicObject) value);
            }
        }

        for (InternalMethod method : methods.values()) {
            method.getAdjacentObjects(adjacent);
        }
    }

    public SourceSection getSourceSection() {
        return sourceSection;
    }

    /**
     * Registers an Assumption for a given method name, which is invalidated when a method with same
     * name is defined or undefined in this class or when a module is prepended to this class.
     * This does not check re-definitions in subclasses.
     */
    public Assumption registerAssumption(String methodName) {
        assert context.getCoreLibrary().isInitializing();
        Assumption assumption = Truffle.getRuntime().createAssumption("inlined " + getName() + "#" + methodName);
        Assumption old = inlinedBuiltinsAssumptions.put(methodName, assumption);
        assert old == null;
        return assumption;
    }

    private void changedMethod(String name) {
        Assumption assumption = inlinedBuiltinsAssumptions.get(name);
        if (assumption != null) {
            assumption.invalidate();
        }
    }

    private void prependInvalidation() {
        if (!inlinedBuiltinsAssumptions.isEmpty()) {
            for (Assumption assumption : inlinedBuiltinsAssumptions.values()) {
                assumption.invalidate();
            }
        }
    }
}

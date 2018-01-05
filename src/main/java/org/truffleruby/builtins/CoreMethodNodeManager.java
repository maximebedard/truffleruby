/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.builtins;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.Layouts;
import org.truffleruby.Log;
import org.truffleruby.RubyContext;
import org.truffleruby.core.RaiseIfFrozenNode;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.cast.TaintResultNode;
import org.truffleruby.core.module.ConstantLookupResult;
import org.truffleruby.core.module.ModuleOperations;
import org.truffleruby.core.numeric.FixnumLowerNodeGen;
import org.truffleruby.core.string.StringUtils;
import org.truffleruby.language.LazyRubyNode;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.RubyRootNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.arguments.MissingArgumentBehavior;
import org.truffleruby.language.arguments.ProfileArgumentNodeGen;
import org.truffleruby.language.arguments.ReadBlockNode;
import org.truffleruby.language.arguments.ReadPreArgumentNode;
import org.truffleruby.language.arguments.ReadRemainingArgumentsNode;
import org.truffleruby.language.arguments.ReadSelfNode;
import org.truffleruby.language.control.JavaException;
import org.truffleruby.language.methods.Arity;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.methods.ExceptionTranslatingNode;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.methods.SharedMethodInfo;
import org.truffleruby.language.objects.SingletonClassNode;
import org.truffleruby.parser.Translator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CoreMethodNodeManager {

    public static final boolean CHECK_DSL_USAGE = System.getenv("TRUFFLE_CHECK_DSL_USAGE") != null;
    private final RubyContext context;
    private final SingletonClassNode singletonClassNode;
    private final PrimitiveManager primitiveManager;

    public CoreMethodNodeManager(RubyContext context, SingletonClassNode singletonClassNode, PrimitiveManager primitiveManager) {
        this.context = context;
        this.singletonClassNode = singletonClassNode;
        this.primitiveManager = primitiveManager;
    }

    public void loadCoreMethodNodes() {
        if (!TruffleOptions.AOT && context.getOptions().LAZY_BUILTINS) {
            BuiltinsClasses.setupBuiltinsLazy(this, primitiveManager);
        } else {
            for (List<? extends NodeFactory<? extends RubyNode>> factory : BuiltinsClasses.getCoreNodeFactories()) {
                addCoreMethodNodes(factory);
            }
        }

        allMethodInstalled();
    }

    public void addCoreMethodNodes(List<? extends NodeFactory<? extends RubyNode>> nodeFactories) {
        String moduleName = null;
        DynamicObject module = null;

        for (NodeFactory<? extends RubyNode> nodeFactory : nodeFactories) {
            final Class<?> nodeClass = nodeFactory.getNodeClass();
            final CoreMethod methodAnnotation = nodeClass.getAnnotation(CoreMethod.class);
            Primitive primitiveAnnotation;

            if (methodAnnotation != null) {
                if (module == null) {
                    CoreClass coreClass = nodeClass.getEnclosingClass().getAnnotation(CoreClass.class);
                    if (coreClass == null) {
                        throw new Error(nodeClass.getEnclosingClass() + " needs a @CoreClass annotation");
                    }
                    moduleName = coreClass.value();
                    module = getModule(moduleName);
                }
                addCoreMethod(module, new MethodDetails(moduleName, methodAnnotation, nodeFactory));
            } else if ((primitiveAnnotation = nodeClass.getAnnotation(Primitive.class)) != null) {
                primitiveManager.addPrimitive(nodeFactory, primitiveAnnotation);
            }
        }
    }

    private DynamicObject getModule(String fullName) {
        DynamicObject module;

        if (fullName.equals("main")) {
            module = getSingletonClass(context.getCoreLibrary().getMainObject());
        } else {
            module = context.getCoreLibrary().getObjectClass();

            for (String moduleName : fullName.split("::")) {
                final ConstantLookupResult constant = ModuleOperations.lookupConstant(context, module, moduleName);

                if (!constant.isFound()) {
                    throw new RuntimeException(StringUtils.format("Module %s not found when adding core library", moduleName));
                }

                module = (DynamicObject) constant.getConstant().getValue();
            }
        }

        assert RubyGuards.isRubyModule(module) : fullName;
        return module;
    }

    private DynamicObject getSingletonClass(Object object) {
        return singletonClassNode.executeSingletonClass(object);
    }

    private void addCoreMethod(DynamicObject module, MethodDetails methodDetails) {
        final CoreMethod method = methodDetails.getMethodAnnotation();

        final String[] names = method.names();
        assert names.length >= 1;

        final Visibility visibility = method.visibility();
        verifyUsage(module, methodDetails, method, visibility);

        final SharedMethodInfo sharedMethodInfo = makeSharedMethodInfo(context, module,
                method.required(), method.optional(), method.rest(), names[0]);
        final CallTarget callTarget = makeGenericMethod(context, methodDetails.getNodeFactory(), methodDetails.getMethodAnnotation(), sharedMethodInfo);

        final boolean onSingleton = method.onSingleton() || method.constructor();
        addMethods(module, method.isModuleFunction(), onSingleton, names, visibility, sharedMethodInfo, callTarget);
    }

    public void addLazyCoreMethod(String nodeFactoryName, String moduleName, Visibility visibility,
            boolean isModuleFunction, boolean onSingleton, int required, int optional, boolean rest, String... names) {
        final DynamicObject module = getModule(moduleName);

        final SharedMethodInfo sharedMethodInfo = makeSharedMethodInfo(context, module, required, optional, rest, names[0]);

        final RubyNode methodNode = new LazyRubyNode(() -> {
            final NodeFactory<? extends RubyNode> nodeFactory = loadNodeFactory(nodeFactoryName);
            final CoreMethod methodAnnotation = nodeFactory.getNodeClass().getAnnotation(CoreMethod.class);
            return createCoreMethodNode(context, nodeFactory, methodAnnotation, sharedMethodInfo);
        });

        final CallTarget callTarget = createCallTarget(context, sharedMethodInfo, methodNode);

        addMethods(module, isModuleFunction, onSingleton, names, visibility, sharedMethodInfo, callTarget);
    }

    private void addMethods(DynamicObject module, boolean isModuleFunction, boolean onSingleton, String[] names,
            Visibility visibility, SharedMethodInfo sharedMethodInfo, CallTarget callTarget) {
        if (isModuleFunction) {
            addMethod(context, module, sharedMethodInfo, callTarget, names, Visibility.PRIVATE);
            addMethod(context, getSingletonClass(module), sharedMethodInfo, callTarget, names, Visibility.PUBLIC);
        } else if (onSingleton) {
            addMethod(context, getSingletonClass(module), sharedMethodInfo, callTarget, names, visibility);
        } else {
            addMethod(context, module, sharedMethodInfo, callTarget, names, visibility);
        }
    }

    private void verifyUsage(DynamicObject module, MethodDetails methodDetails, final CoreMethod method, final Visibility visibility) {
        if (method.isModuleFunction()) {
            if (visibility != Visibility.PUBLIC) {
                Log.LOGGER.warning("visibility ignored when isModuleFunction in " + methodDetails.getIndicativeName());
            }
            if (method.onSingleton()) {
                Log.LOGGER.warning("either onSingleton or isModuleFunction for " + methodDetails.getIndicativeName());
            }
            if (method.constructor()) {
                Log.LOGGER.warning("either constructor or isModuleFunction for " + methodDetails.getIndicativeName());
            }
            if (RubyGuards.isRubyClass(module)) {
                Log.LOGGER.warning("using isModuleFunction on a Class for " + methodDetails.getIndicativeName());
            }
        }
        if (method.onSingleton() && method.constructor()) {
            Log.LOGGER.warning("either onSingleton or constructor for " + methodDetails.getIndicativeName());
        }

        if (methodDetails.getPrimaryName().equals("allocate") && !methodDetails.getModuleName().equals("Class")) {
            Log.LOGGER.warning("do not define #allocate but #__allocate__ for " + methodDetails.getIndicativeName());
        }
        if (methodDetails.getPrimaryName().equals("__allocate__") && method.visibility() != Visibility.PRIVATE) {
            Log.LOGGER.warning(methodDetails.getIndicativeName() + " should be private");
        }
    }

    private static void addMethod(RubyContext context, DynamicObject module, SharedMethodInfo sharedMethodInfo, CallTarget callTarget, String[] names, Visibility originalVisibility) {
        assert RubyGuards.isRubyModule(module);

        for (String name : names) {
            Visibility visibility = originalVisibility;
            if (ModuleOperations.isMethodPrivateFromName(name)) {
                visibility = Visibility.PRIVATE;
            }
            final InternalMethod method = new InternalMethod(context, sharedMethodInfo, sharedMethodInfo.getLexicalScope(), DeclarationContext.NONE, name, module, visibility, false, callTarget);

            Layouts.MODULE.getFields(module).addMethod(context, null, method);
        }
    }

    private static SharedMethodInfo makeSharedMethodInfo(RubyContext context, DynamicObject module,
            int required, int optional, boolean rest, String primaryName) {
        final LexicalScope lexicalScope = new LexicalScope(context.getRootLexicalScope(), module);

        return new SharedMethodInfo(
                context.getCoreLibrary().getSourceSection(),
                lexicalScope,
                new Arity(required, optional, rest),
                module,
                primaryName,
                "builtin",
                null,
                context.getOptions().CORE_ALWAYS_CLONE);
    }

    private static CallTarget makeGenericMethod(RubyContext context, NodeFactory<? extends RubyNode> nodeFactory, CoreMethod method, SharedMethodInfo sharedMethodInfo) {
        final RubyNode methodNode;
        if (!TruffleOptions.AOT && context.getOptions().LAZY_CORE_METHOD_NODES) {
            methodNode = new LazyRubyNode(() -> createCoreMethodNode(context, nodeFactory, method, sharedMethodInfo));
        } else {
            methodNode = createCoreMethodNode(context, nodeFactory, method, sharedMethodInfo);
        }

        return createCallTarget(context, sharedMethodInfo, methodNode);
    }

    private static CallTarget createCallTarget(RubyContext context, SharedMethodInfo sharedMethodInfo, RubyNode methodNode) {
        final RubyRootNode rootNode = new RubyRootNode(context, sharedMethodInfo.getSourceSection(), null, sharedMethodInfo, methodNode);
        return Truffle.getRuntime().createCallTarget(rootNode);
    }

    public static RubyNode createCoreMethodNode(RubyContext context, NodeFactory<? extends RubyNode> nodeFactory, CoreMethod method, SharedMethodInfo sharedMethodInfo) {
        final List<RubyNode> argumentsNodes = new ArrayList<>();

        final boolean needsSelf = needsSelf(method);

        if (needsSelf) {
            RubyNode readSelfNode = ProfileArgumentNodeGen.create(new ReadSelfNode());
            argumentsNodes.add(transformArgument(method, readSelfNode, 0));
        }

        final int required = method.required();
        final int optional = method.optional();
        final int nArgs = required + optional;

        if (CHECK_DSL_USAGE) {
            AmbiguousOptionalArgumentChecker.verifyNoAmbiguousOptionalArguments(nodeFactory, method);
            LowerFixnumChecker.checkLowerFixnumArguments(nodeFactory, needsSelf ? 1 : 0, method.lowerFixnum());
        }

        for (int n = 0; n < nArgs; n++) {
            RubyNode readArgumentNode = ProfileArgumentNodeGen.create(new ReadPreArgumentNode(n, MissingArgumentBehavior.UNDEFINED));
            argumentsNodes.add(transformArgument(method, readArgumentNode, n + 1));
        }

        if (method.rest()) {
            argumentsNodes.add(new ReadRemainingArgumentsNode(nArgs));
        }

        if (method.needsBlock()) {
            argumentsNodes.add(new ReadBlockNode(NotProvided.INSTANCE));
        }

        RubyNode node = createNodeFromFactory(context, nodeFactory, argumentsNodes);

        final RubyNode checkArity = Translator.createCheckArityNode(sharedMethodInfo.getArity());

        node = transformResult(context, method, node);
        node = Translator.sequence(context.getCoreLibrary().getSourceIndexLength(), Arrays.asList(checkArity, node));

        return new ExceptionTranslatingNode(node, method.unsupportedOperationBehavior());
    }

    public static <T> T createNodeFromFactory(RubyContext context, NodeFactory<? extends T> nodeFactory, List<RubyNode> argumentsNodes) {
        final List<List<Class<?>>> signatures = nodeFactory.getNodeSignatures();

        assert signatures.size() == 1;
        final List<Class<?>> signature = signatures.get(0);

        if (signature.size() == 0) {
            return nodeFactory.createNode();
        } else {
            final RubyNode[] argumentsArray = argumentsNodes.toArray(new RubyNode[argumentsNodes.size()]);
            if (signature.size() == 1 && signature.get(0) == RubyNode[].class) {
                Object args = argumentsArray;
                return nodeFactory.createNode(args);
            } else {
                Object[] args = argumentsArray;
                return nodeFactory.createNode(args);
            }
        }
    }

    public static boolean needsSelf(CoreMethod method) {
        // Do not use needsSelf=true in module functions, it is either the module/class or the instance.
        // Usage of needsSelf is quite rare for singleton methods (except constructors).
        return method.constructor() || (!method.isModuleFunction() && !method.onSingleton() && method.needsSelf());
    }

    private static RubyNode transformArgument(CoreMethod method, RubyNode argument, int n) {
        if (ArrayUtils.contains(method.lowerFixnum(), n)) {
            argument = FixnumLowerNodeGen.create(argument);
        }

        if (n == 0 && method.raiseIfFrozenSelf()) {
            argument = new RaiseIfFrozenNode(argument);
        }

        return argument;
    }

    private static RubyNode transformResult(RubyContext context, CoreMethod method, RubyNode node) {
        if (!method.enumeratorSize().isEmpty()) {
            assert !method.returnsEnumeratorIfNoBlock() : "Only one of enumeratorSize or returnsEnumeratorIfNoBlock can be specified";
            // TODO BF 6-27-2015 Handle multiple method names correctly
            node = new EnumeratorSizeNode(method.enumeratorSize(), method.names()[0], node);
        } else if (method.returnsEnumeratorIfNoBlock()) {
            // TODO BF 3-18-2015 Handle multiple method names correctly
            node = new ReturnEnumeratorIfNoBlockNode(method.names()[0], node);
        }

        if (method.taintFrom() != -1) {
            final boolean taintFromSelf = method.taintFrom() == 0;
            final int taintFromArg = taintFromSelf ? -1 : method.taintFrom() - 1;
            node = new TaintResultNode(taintFromSelf, taintFromArg, node);
        }

        return node;
    }

    @SuppressWarnings("unchecked")
    public static NodeFactory<? extends RubyNode> loadNodeFactory(String nodeFactoryName) {
        final Object instance;
        try {
            Class<?> nodeFactoryClass = Class.forName(nodeFactoryName);
            instance = nodeFactoryClass.getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new JavaException(e);
        }
        return (NodeFactory<? extends RubyNode>) instance;
    }

    public void allMethodInstalled() {
        if (CHECK_DSL_USAGE) {
            if (!(AmbiguousOptionalArgumentChecker.SUCCESS && LowerFixnumChecker.SUCCESS)) {
                System.exit(1);
            }
        }
    }

    public static class MethodDetails {

        private final String moduleName;
        private final CoreMethod methodAnnotation;
        private final NodeFactory<? extends RubyNode> nodeFactory;

        public MethodDetails(String moduleName, CoreMethod methodAnnotation, NodeFactory<? extends RubyNode> nodeFactory) {
            this.moduleName = moduleName;
            this.methodAnnotation = methodAnnotation;
            this.nodeFactory = nodeFactory;
        }

        public CoreMethod getMethodAnnotation() {
            return methodAnnotation;
        }

        public NodeFactory<? extends RubyNode> getNodeFactory() {
            return nodeFactory;
        }

        public String getModuleName() {
            return moduleName;
        }

        public String getPrimaryName() {
            return methodAnnotation.names()[0];
        }

        public String getIndicativeName() {
            return moduleName + "#" + getPrimaryName();
        }
    }

}

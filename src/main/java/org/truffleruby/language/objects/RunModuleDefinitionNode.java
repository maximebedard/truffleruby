/*
 * Copyright (c) 2013, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.language.objects;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.methods.ModuleBodyDefinitionNode;

public class RunModuleDefinitionNode extends RubyNode {

    final protected LexicalScope lexicalScope;

    @Child private RubyNode definingModule;
    @Child private ModuleBodyDefinitionNode definitionMethod;
    @Child private IndirectCallNode callModuleDefinitionNode = Truffle.getRuntime().createIndirectCallNode();

    public RunModuleDefinitionNode(LexicalScope lexicalScope,
                                   ModuleBodyDefinitionNode definition, RubyNode definingModule) {
        this.definingModule = definingModule;
        this.definitionMethod = definition;
        this.lexicalScope = lexicalScope;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        final DynamicObject module = (DynamicObject) definingModule.execute(frame);
        definitionMethod.execute(frame); // for tracing
        final InternalMethod definition = definitionMethod.createMethod(frame, lexicalScope, module);

        final DeclarationContext parentContext = RubyArguments.getDeclarationContext(frame);
        final DeclarationContext newContext = definition.getDeclarationContext().withRefinements(parentContext.getRefinements());

        // TODO (eregon): check this DeclarationContext
        return callModuleDefinitionNode.call(definition.getCallTarget(), RubyArguments.pack(
                null, null, definition, newContext, null, module, null, RubyNode.EMPTY_ARGUMENTS));
    }

}

package org.truffleruby.language.methods;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.Layouts;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.control.RaiseException;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

@NodeChildren({
        @NodeChild("scopeNode"),
        @NodeChild("moduleNode")
})
public abstract class UsingNode extends RubyNode {

    public abstract DynamicObject executeUsing(LexicalScope scope, DynamicObject module);

    @Specialization(guards = "isRubyModule(module)")
    public DynamicObject using(LexicalScope scope, DynamicObject module) {
        if(RubyGuards.isRubyClass(module)){
            throw new RaiseException(coreExceptions().typeErrorWrongArgumentType(module, "Module", this));
        }
        usingModuleRecursive(scope, module);
        return nil();
    }

    @TruffleBoundary
    private void usingModuleRecursive(LexicalScope scope, DynamicObject module){
        // TODO BJF Review/add activating refinements recursively in module parents
        ConcurrentMap<DynamicObject, DynamicObject> refinements = Layouts.MODULE.getFields(module).getRefinements();
        if(refinements.isEmpty()){
            return;
        }
        for (Map.Entry<DynamicObject, DynamicObject> entry : refinements.entrySet()) {
            usingRefinement(entry.getKey(), entry.getValue(), scope);
        }
    }

    private void usingRefinement(DynamicObject refinedClass, DynamicObject refinementModule, LexicalScope lexicalScope){
        Map<DynamicObject, DynamicObject> scopeRefinements = lexicalScope.getRefinements();
        if(scopeRefinements.containsKey(refinedClass)){
            // TODO BJF Review the recursive checking
            return; // Already using this refinement
        }
        Layouts.MODULE.getFields(refinementModule).setOverlaid(true);
        final DynamicObject includedRefinement = Layouts.MODULE.getFields(refinedClass).includeModule(getContext(), refinementModule);
        scopeRefinements.put(refinedClass, includedRefinement);
    }

}

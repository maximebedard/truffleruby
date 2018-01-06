package org.truffleruby.language.methods;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.Layouts;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.control.RaiseException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

@NodeChild("moduleNode")
public abstract class UsingNode extends RubyNode {

    public abstract DynamicObject executeUsing(DynamicObject module);

    @Specialization(guards = "isRubyModule(module)")
    public DynamicObject using(DynamicObject module) {
        if (RubyGuards.isRubyClass(module)) {
            throw new RaiseException(coreExceptions().typeErrorWrongArgumentType(module, "Module", this));
        }

        final Frame callerFrame = getContext().getCallStack().getCallerFrameIgnoringSend().getFrame(FrameInstance.FrameAccess.READ_WRITE);
        final DeclarationContext declarationContext = RubyArguments.getDeclarationContext(callerFrame);
        final Map<DynamicObject, DynamicObject[]> newRefinements = usingModuleRecursive(declarationContext, module);
        DeclarationContext.setRefinements(callerFrame, declarationContext, newRefinements);

        return nil();
    }

    @TruffleBoundary
    private Map<DynamicObject, DynamicObject[]> usingModuleRecursive(DeclarationContext declarationContext, DynamicObject module) {
        final Map<DynamicObject, DynamicObject[]> newRefinements = new HashMap<>(declarationContext.getRefinements());

        // Iterate ancestors in reverse order so refinements upper in the chain have precedence
        final Deque<DynamicObject> reverseAncestors = new ArrayDeque<>();
        for (DynamicObject ancestor : Layouts.MODULE.getFields(module).ancestors()) {
            reverseAncestors.addFirst(ancestor);
        }

        for (DynamicObject ancestor : reverseAncestors) {
            final ConcurrentMap<DynamicObject, DynamicObject> refinements = Layouts.MODULE.getFields(ancestor).getRefinements();
            for (Map.Entry<DynamicObject, DynamicObject> entry : refinements.entrySet()) {
                usingRefinement(entry.getKey(), entry.getValue(), newRefinements);
            }
        }

        return newRefinements;
    }

    private void usingRefinement(DynamicObject refinedClass, DynamicObject refinementModule, Map<DynamicObject, DynamicObject[]> newRefinements) {
        final DynamicObject[] refinements = newRefinements.get(refinedClass);
        if (refinements == null) {
            newRefinements.put(refinedClass, new DynamicObject[]{ refinementModule });
        } else {
            if (ArrayUtils.contains(refinements, refinementModule)) {
                // Already using this refinement
            } else {
                // Add new refinement in front
                newRefinements.put(refinedClass, unshift(refinements, refinementModule));
            }
        }

//        if (refinement != null) {
//            // TODO BJF Review the recursive checking
//            newRefinements.put(refinedClass, refinement);
//            return; // Already using this refinement
//        }
//         Layouts.MODULE.getFields(refinementModule).setOverlaid(true);
//         final DynamicObject includedRefinement = Layouts.MODULE.getFields(refinedClass).includeModule(getContext(), refinementModule);
//         newRefinements.put(refinedClass, includedRefinement);
    }

    private static DynamicObject[] unshift(DynamicObject[] array, DynamicObject element) {
        final DynamicObject[] newArray = new DynamicObject[1 + array.length];
        newArray[0] = element;
        System.arraycopy(array, 0, newArray, 1, array.length);
        return newArray;
    }

}

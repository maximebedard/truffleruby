package org.truffleruby.core.thread;

import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.core.array.ArrayGuards;
import org.truffleruby.core.array.ArrayMirror;
import org.truffleruby.core.array.ArrayStrategy;
import org.truffleruby.core.binding.BindingNodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.object.DynamicObject;

@CoreClass("Truffle::ThreadOperations")
public class TruffleThreadNodes {

    @CoreMethod(names = "ruby_caller", isModuleFunction = true, required = 2)
    @ImportStatic(ArrayGuards.class)
    public abstract static class FindRubyCaller extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = { "isRubyArray(modules)", "strategy.matches(modules)" }, limit = "ARRAY_STRATEGIES")
        public DynamicObject findRubyCaller(int skip, DynamicObject modules,
                @Cached("of(modules)") ArrayStrategy strategy) {
            ArrayMirror mirror = strategy.newMirror(modules);
            Object[] moduleArray = mirror.getBoxedCopy();
            Frame rubyCaller = getContext().getCallStack().getCallerFrameNotInModules(moduleArray, skip).getFrame(FrameInstance.FrameAccess.MATERIALIZE);
            return rubyCaller == null ? nil() : BindingNodes.createBinding(getContext(), rubyCaller.materialize());
        }

    }
}

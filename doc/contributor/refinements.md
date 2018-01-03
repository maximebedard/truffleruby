# Refinements Implementation Details
This document provides an overview TruffleRuby`s refinements implementation.

## `refine` Implementation
This example creates a refinement `R` under the module `M` for the refined class `C`.
```
class C
  def one
    puts "one unrefined"
  end
end
```

The method `refine` is used inside a `Module` to create a refinement. 
```
module M
  refine C do  
     def one
       puts "one refined"
     end
  end
end
```
The `refine` block is module eval'ed into a new anonymous module `R`. `R` is the refinement module and contains the method definitions from the refine block after they are eval'ed.  If the refined class `C` contains an existing method with the same name, the original method `one` in this example, the existing method will be marked as refined.

Next, `refine` puts the new `R` module into module `M`'s refinements tables,  which is a map of refined classes to refinement modules. This specify entry will contain the key `C` (refined class) and the value `R` (anonymous refinement module).


## `using` Implementation

```
module Test

  # refinements not active
  using M
  # refinements active
  
  def two
    C.new.one
  end
  
end
```
The `using` method makes the refinements in module M active in the lexical scope that follows its call.  It does this by appending module `M`'s refinements table entries to the caller frame's declaration context.

The `two` method in the `Test` module will save the frame's declaration context when defined.  When the `two` method is called, it will place it's declaration context into the frame so the refinements are seen in the method body.

## Method dispatch
When a refined method is called, the frame's declaration context will be checked to see if there are active refinements. If an active refinement method is found for the method and receiver, the refinement method will be called.

## References
- [Refinements Spec](https://bugs.ruby-lang.org/projects/ruby-trunk/wiki/RefinementsSpec)
- [Refinements Docs](https://ruby-doc.org/core-2.3.0/doc/syntax/refinements_rdoc.html)
- [Module#refine](https://ruby-doc.org/core-2.3.0/Module.html#method-i-refine)
- [Module#using](https://ruby-doc.org/core-2.3.0/Module.html#method-i-using)
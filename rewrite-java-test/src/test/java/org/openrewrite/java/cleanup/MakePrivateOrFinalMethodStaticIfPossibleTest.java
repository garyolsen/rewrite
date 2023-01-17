package org.openrewrite.java.cleanup;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MakePrivateOrFinalMethodStaticIfPossibleTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MakePrivateOrFinalMethodStaticIfPossible());
    }

    @Test
    void skipsNestedClasses() {
        // This is a limitation to simplify the implementation and ensure
        // that any changes made are correct.
        rewriteRun(
          java(
            """
            class A {                
                static class B {
                    private int y() {
                        return 42;
                    }
                }
            }
            """
          )
        );
    }

    @Test
    void skipsMethodsContainingAnonymousClassInstantiation() {
        // This is a deliberate limitation to simplify the implementation.
        // The current implementation assumes that expressions only have a single
        // enclosing scope that `this` could refer to.
        // The example method *could* be made static.
        rewriteRun(
          java(
            """
            class A {            
                int x;            
                
                private int y() {
                    return new Object() {
                    };
                }
            }
            """
          )
        );
    }

    @Test
    void skipsJavaIoSerializableMethods() {
        rewriteRun(
          java(
            """
            class A implements java.io.Serializable {
               private void writeObject(java.io.ObjectOutputStream out) throws IOException {}
               private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {}
               private void readObjectNoData() throws ObjectStreamException {}
            }
            """
          )
        );
    }

    @Test
    void changesModifiersCorrectly() {
        rewriteRun(
          java(
            """
            class A {
                private int test() {
                    return 34;
                }
                
                final int test2() {
                    return 34;
                }
                
                private final int test3() {
                    return 34;
                }
            }
            """,
            """
            class A {
                private static int test() {
                    return 34;
                }
                
                static int test2() {
                    return 34;
                }
                
                private static int test3() {
                    return 34;
                }
            }
            """
          )
        );
    }

    @Test
    void membersOfFinalClassAreConsideredFinal() {
        rewriteRun(
          java(
            """
            final class A {
                int test() {
                    return 34;
                }
            }
            """,
            """
            final class A {
                static int test() {
                    return 34;
                }
            }
            """
          )
        );
    }

    @Test
    void ownFieldAccessedOnMethodArgument() {
        // check that the implementation does not naively assume that
        // field accesses owned by the containing class are targeting `this`
        rewriteRun(
          java(
            """
            class A {
                int x;
                
                private int test(A other) {
                    return other.x;
                }
            }
            """,
            """
            class A {
                int x;
                
                private static int test(A other) {
                    return other.x;
                }
            }
            """
          )
        );
    }

    @Test
    void implicitStaticThisFieldAccess() {
        rewriteRun(
          java(
            """
            class A {
                static int x;
                
                private int test() {
                    return x;
                }
            }
            """,
            """
            class A {
                static int x;
                
                private static int test() {
                    return x;
                }
            }
            """
          )
        );
    }

    @Test
    void implicitThisMethodInvoked() {
        rewriteRun(
          java(
            """
            class A {
                int y;
            
                private int test() {
                    return x();
                }
                
                int x() {
                    return y;
                }
            }
            """
          )
        );
    }


    @Test
    void implicitThisStaticMethodInvoked() {
        rewriteRun(
          java(
            """
            class A {
                static int y;
            
                private int test() {
                    return x();
                }
                
                static int x() {
                    return y;
                }
            }
            """,
            """
            class A {
                static int y;
            
                private static int test() {
                    return x();
                }
                
                static int x() {
                    return y;
                }
            }
            """
          )
        );
    }

    @Test
    void explicitThisMethodInvoked() {
        rewriteRun(
          java(
            """
            class A {
                int y;
            
                private int test() {
                    return this.x();
                }
                
                int x() {
                    return y;
                }
            }
            """
          )
        );
    }

    @Test
    void ownMethodInvokedOnMethodArgument() {
        // check that implementation does not naively assume that
        // a method is being invoked on `this` just because it is owned
        // by the enclosing class
        rewriteRun(
          java(
            """
            class A {
                private int test(A other) {
                    return other.x();
                }
                
                int x() {
                    return 42;
                }
            }
            """,
            """
            class A {
                private static int test(A other) {
                    return other.x();
                }
                
                int x() {
                    return 42;
                }
            }
            """
          )
        );
    }

    @Test
    void explicitThisFieldAccess() {
        rewriteRun(
          java(
            """
            class A {
                private int stuff;
                
                private int test() {
                    return this.stuff;
                }
            }
            """
          )
        );
    }

    @Test
    void implicitThisFieldAccess() {
        rewriteRun(
          java(
            """
            class A {
                private int stuff;
                
                private int test() {
                    return stuff;
                }
            }
            """
          )
        );
    }


    @Test
    void methodContainingImplicitThisEnclosingNew() {
        // in this example, `new B()` is actually `this.new B()`
        rewriteRun(
          java(
            """
            class A {
            
                class B {
                    final static int x = 42;
                }
            
                final int test() {
                    return new B().x;
                }
            }
            """
          )
        );
    }

    @Test
    void methodContainingExplicitThisEnclosingNew() {
        rewriteRun(
          java(
            """
            class A {
            
                class B {
                    final static int x = 42;
                }
            
                final int test() {
                    return this.new B().x;
                }
            }
            """
          )
        );
    }

    @Test
    void methodContainingExplicitEnclosingNew() {
        // check that the implementation does not naively assume that
        // a nested class being instantiated means that the enclosing
        // expression is `this`
        rewriteRun(
          java(
            """
            class A {
            
                class B {
                    final static int x = 42;
                }
            
                final int test(A other) {
                    return other.new B().x;
                }
            }
            """,
            """
            class A {
            
                class B {
                    final static int x = 42;
                }
            
                static int test(A other) {
                    return other.new B().x;
                }
            }
            """
          )
        );
    }


    @Test
    void methodContainingUnrelatedFieldAccess() {
        // sanity-check that an excessively-broad condition for detecting
        // field accesses on `this` isn't in place
        rewriteRun(
          java(
            """
            class A {
                private int test() {
                    return Integer.MAX_VALUE;
                }
            }
            """,
            """
            class A {
                private static int test() {
                    return Integer.MAX_VALUE;
                }
            }
            """
          )
        );
    }

    @Test
    void methodContainingUnrelatedMethodInvocation() {
        // sanity-check that an excessively-broad condition for detecting
        // methods with a `this` target isn't in place
        rewriteRun(
          java(
            """
            class A {
                private int test() {
                    return "".length();
                }
            }
            """,
            """
            class A {
                private static int test() {
                    return "".length();
                }
            }
            """
          )
        );
    }

    @Test
    void methodContainingUnrelatedNewClass() {
        // sanity-check that an excessively-broad condition for detecting
        // instantiations with an enclosing `this` isn't in place
        rewriteRun(
          java(
            """
            class A {
                private int test() {
                    return new Object();
                }
            }
            """,
            """
            class A {
                private static int test() {
                    return new Object();
                }
            }
            """
          )
        );
    }
}

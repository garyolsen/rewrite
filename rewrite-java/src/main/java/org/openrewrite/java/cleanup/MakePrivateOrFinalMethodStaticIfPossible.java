package org.openrewrite.java.cleanup;

import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MakePrivateOrFinalMethodStaticIfPossible extends Recipe {
    @Override
    public String getDisplayName() {
        return "Make private/final methods static if possible";
    }

    @Override
    public String getDescription() {
        return "If a private or final method does not access instance data, it should be static.";
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        // TODO: Is it better practice to explicitly declare a named visitor class?
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext executionContext) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, executionContext);

                if (isMethodDeclaredInNestedClass(m)) {
                    // Initial limitation to make the implementation simpler and less risky.
                    // If this limitation is lifted, the tests will break (to ensure this is not unintentional).
                    return m;
                }

                if (!isMethodEffectivelyFinalInstanceMethod(m, getCursor())) {
                    return m;
                }

                if (isMethodForJavaIoSerializable(m, getCursor())) {
                    return m;
                }

                if (doesMethodMaybeReferenceThis(m, getCursor())) {
                    return m;
                }

                J.Modifier staticModifier = new J.Modifier(
                        Tree.randomId(),
                        Space.build(" ", Collections.emptyList()),
                        Markers.EMPTY,
                        J.Modifier.Type.Static,
                        Collections.emptyList()
                );

                List<J.Modifier> newModifiers = ModifierOrder.sortModifiers(
                        // The modifier list will *always* ultimately be modified, so it's okay to just use streams.
                        Stream.concat(
                                // Need to remove `final` modifier because it is redundant on a static method.
                                m.getModifiers().stream().filter(p -> p.getType() != J.Modifier.Type.Final),
                                // Add `static`
                                Stream.of(staticModifier)
                        ).collect(Collectors.toList())
                );

                // TODO: Is it better practice to limit the autoFormat to only the modifier list?
                return autoFormat(m.withModifiers(newModifiers), executionContext);
            }
        };
    }

    private static boolean isMethodDeclaredInNestedClass(J.MethodDeclaration method) {
        JavaType.Method type = method.getMethodType();
        return type.getDeclaringType().getOwningClass() != null;
    }

    private static boolean isMethodEffectivelyFinalInstanceMethod(J.MethodDeclaration method, Cursor cursor) {
        // method is static --> not an instance method
        if (method.hasModifier(J.Modifier.Type.Static)) {
            return false;
        }

        // final or private modifier --> ok
        if (method.hasModifier(J.Modifier.Type.Final) || method.hasModifier(J.Modifier.Type.Private)) {
            return true;
        }

        J.ClassDeclaration classDecl = cursor.firstEnclosing(J.ClassDeclaration.class);
        // not inside a class... ??? definitely not ok
        if (classDecl == null) {
            return false;
        }

        // declared in a final class --> ok
        if (classDecl.hasModifier(J.Modifier.Type.Final)) {
            return true;
        }

        return false;
    }

    private static boolean isMethodForJavaIoSerializable(J.MethodDeclaration method, Cursor cursor) {
        // if the containing class is `java.io.Serializable`, ignore the relevant methods
        J.ClassDeclaration classDeclaration = cursor.firstEnclosing(J.ClassDeclaration.class);
        if (classDeclaration == null) {
            return false;
        }
        if (TypeUtils.isAssignableTo("java.io.Serializable", classDeclaration.getType())) {
            // this is potentially over-broad if the methods are overloaded, but that seems uncommon
            switch (method.getSimpleName()) {
                case "readObject":
                case "readObjectNoData":
                case "readResolve":
                case "writeObject":
                    return true;
            }
        }
        return false;
    }

    private static boolean doesMethodMaybeReferenceThis(J.MethodDeclaration method, Cursor cursor) {
        JavaIsoVisitor<AtomicBoolean> visitor = new JavaIsoVisitor<AtomicBoolean>() {

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, AtomicBoolean maybeHasThis) {
                if (isImplicitEnclosingThisNewClass(newClass)) {
                    maybeHasThis.set(true);
                } else if (newClass.getBody() != null) {
                    // inside the anonymous class body, it's harder to determine which scope
                    // an implied `this` refers to, so for now we just bail
                    maybeHasThis.set(true);
                }
                return super.visitNewClass(newClass, maybeHasThis);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean maybeHasThis) {
                if (isExplicitThis(identifier) || isImplicitThisFieldAccess(identifier, getCursor()) || isImplicitThisMethodInvocation(identifier, getCursor())) {
                    maybeHasThis.set(true);
                }
                return super.visitIdentifier(identifier, maybeHasThis);
            }
        };

        AtomicBoolean hasThis = new AtomicBoolean(false);
        visitor.visit(method.getBody(), hasThis);
        return hasThis.get();
    }

    private static boolean isExplicitThis(J.Identifier identifier) {
        return identifier.getSimpleName().equals("this");
    }

    private static boolean isImplicitThisMethodInvocation(J.Identifier identifier, Cursor cursor) {
        Object parent = cursor.dropParentWhile(p -> p instanceof JRightPadded).getValue();

        // not part of a method invocation
        if (!(parent instanceof J.MethodInvocation)) {
            return false;
        }

        // method invocation has a target
        // e.g. `x.identifier()`
        if (((J.MethodInvocation) parent).getSelect() != null) {
            return false;
        }

        // identifier is not the name of the method invocation
        if (((J.MethodInvocation) parent).getName().getId() != identifier.getId()) {
            return false;
        }

        // not an instance method
        JavaType.@Nullable Method methodType = ((J.MethodInvocation) parent).getMethodType();
        if (methodType == null || methodType.hasFlags(Flag.Static)) {
            return false;
        }

        return true;
    }

    private static boolean isImplicitThisFieldAccess(J.Identifier identifier, Cursor cursor) {
        // not a variable
        if (identifier.getFieldType() == null) {
            return false;
        }

        // not sure what this would even mean
        if (identifier.getFieldType().getOwner() == null) {
            return false;
        }

        // not a field
        if (!(identifier.getFieldType().getOwner() instanceof JavaType.Class)) {
            return false;
        }

        // accessed field is static
        if (identifier.getFieldType().hasFlags(Flag.Static)) {
            return false;
        }

        // check if the ident is the Name of a FieldAccess, i.e. `<target>.<name>`
        // if so, it can't also have an implicit `this` qualification (because the target is explicit)
        Object parent = cursor.dropParentWhile(p -> p instanceof JLeftPadded).getValue();
        if (parent instanceof J.FieldAccess) {
            J.Identifier nameIdentifier = ((J.FieldAccess) parent).getName();
            if (nameIdentifier.getId().equals(identifier.getId())) {
                return false;
            }
        }

        return true;
    }

    private static boolean isImplicitEnclosingThisNewClass(J.NewClass newClass) {
        JavaType newType = newClass.getClazz().getType();
        if (!(newType instanceof JavaType.FullyQualified)) {
            // just to be safe; this should not be the case
            return true;
        }

        // if the class *has* an owner, but it isn't specified in the `new` expression
        JavaType.FullyQualified owner = ((JavaType.FullyQualified) newType).getOwningClass();
        if (owner != null && newClass.getEnclosing() == null) {
            return true;
        }

        return false;
    }
}

package phase4j.r8;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Small isolated reproducer based on Google's issue359385828 regression. */
public class AnnotationProbe {
    @Retention(RetentionPolicy.RUNTIME)
    public @interface A { B b(); B[] bs() default {}; }
    @Retention(RetentionPolicy.RUNTIME)
    public @interface B { String x(); }
    @A(b=@B(x="1"),bs={@B(x="2"),@B(x="3")})
    public static class Annotated {}
    public static void main(String[] args) {
        System.out.println(Annotated.class.getAnnotation(A.class) != null ? "Found" : "Missing");
    }
}

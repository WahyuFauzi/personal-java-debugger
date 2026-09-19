package tool.java_debug_launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SimpleSourceLookUpProviderTest {

    @Test
    void packagedClassUsesFullyQualifiedName() {
        String uri = "/Users/dev/project/src/main/java/com/example/Main.java";
        String source = "package com.example;\n\npublic class Main {}\n";
        assertEquals("com.example.Main", SimpleSourceLookUpProvider.deriveClassName(uri, source));
    }

    @Test
    void defaultPackageUsesSimpleName() {
        String uri = "/Users/dev/project/samples/SampleTarget.java";
        String source = "public class SampleTarget {}\n";
        assertEquals("SampleTarget", SimpleSourceLookUpProvider.deriveClassName(uri, source));
    }

    @Test
    void relativePathWithoutPackageUsesSimpleName() {
        String uri = "samples/SampleTarget.java";
        assertEquals("SampleTarget",
                SimpleSourceLookUpProvider.deriveClassName(uri, "class SampleTarget {}"));
    }

    @Test
    void relativePathWithPackageKeepsPackageRatherThanDirectory() {
        String uri = "src/main/java/com/acme/deep/Foo.java";
        String source = "package com.acme.deep;\nclass Foo {}\n";
        assertEquals("com.acme.deep.Foo", SimpleSourceLookUpProvider.deriveClassName(uri, source));
    }

    @Test
    void windowsPathWithPackageUsesFullyQualifiedName() {
        String uri = "C:\\work\\src\\com\\acme\\deep\\Foo.java";
        String source = "package com.acme.deep;\nclass Foo {}\n";
        assertEquals("com.acme.deep.Foo", SimpleSourceLookUpProvider.deriveClassName(uri, source));
    }

    @Test
    void licenseHeaderBeforePackageIsIgnored() {
        String uri = "/x/com/acme/Bar.java";
        String source = "/*\n * Copyright\n */\npackage com.acme;\npublic class Bar {}\n";
        assertEquals("com.acme.Bar", SimpleSourceLookUpProvider.deriveClassName(uri, source));
    }

    @Test
    void trailingCommentAfterPackageIsStripped() {
        String uri = "/x/com/acme/Baz.java";
        String source = "package com.acme; // keep me\npublic class Baz {}\n";
        assertEquals("com.acme.Baz", SimpleSourceLookUpProvider.deriveClassName(uri, source));
    }

    @Test
    void missingSourceContentsFallsBackToSimpleName() {
        String uri = "/x/com/acme/Qux.java";
        assertEquals("Qux", SimpleSourceLookUpProvider.deriveClassName(uri, ""));
        assertEquals("Qux", SimpleSourceLookUpProvider.deriveClassName(uri, null));
    }
}

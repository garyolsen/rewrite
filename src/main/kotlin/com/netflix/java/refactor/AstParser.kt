package com.netflix.java.refactor

import com.sun.tools.javac.comp.Enter
import com.sun.tools.javac.file.JavacFileManager
import com.sun.tools.javac.main.JavaCompiler
import com.sun.tools.javac.tree.JCTree
import com.sun.tools.javac.util.Context
import java.io.File
import java.util.regex.Pattern
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation

class AstParser {
    companion object {
        fun fullyQualifiedName(sourceStr: String): String? {
            val pkgMatcher = Pattern.compile("\\s*package\\s+([\\w\\.]+)").matcher(sourceStr)
            val pkg = if (pkgMatcher.find()) pkgMatcher.group(1) + "." else ""

            val classMatcher = Pattern.compile("\\s*(class|interface|enum)\\s+(\\w+)").matcher(sourceStr)
            return if (classMatcher.find()) pkg + classMatcher.group(2) else null
        }
    }
    
    val context = Context()
    val compiler = JavaCompiler(context)
    
    init {
        // otherwise the JavacParser will use EmptyEndPosTable, effectively setting -1 as the end position 
        // for every tree element
        compiler.genEndPos = true
    }

    /**
     * TODO look at JavacTool line 36 (JDK 8) for hints about how to quiet error logging during parsing?
     */
    fun parseFiles(files: Iterable<File>, classPath: Iterable<File>? = null): List<JCTree.JCCompilationUnit> {
        val fm = context.get(JavaFileManager::class.java)
        
        if(classPath != null) // override classpath
            (fm as JavacFileManager).setLocation(StandardLocation.CLASS_PATH, classPath)
        
        return files.map { f ->
            compiler.parse(object : SimpleJavaFileObject(f.toURI(), JavaFileObject.Kind.SOURCE) {
                override fun getCharContent(ignoreEncodingErrors: Boolean) = f.readText()
            })
        }.enterAll()
    }
    
    /**
     * Enter symbol definitions into each compilation unit's scope
     */
    private fun List<JCTree.JCCompilationUnit>.enterAll(): List<JCTree.JCCompilationUnit> {
        val enter = Enter.instance(context)
        val compilationUnits = com.sun.tools.javac.util.List.from(this.toTypedArray())
        enter.main(compilationUnits)
        return this
    }
}
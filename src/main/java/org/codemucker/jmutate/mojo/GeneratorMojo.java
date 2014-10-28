package org.codemucker.jmutate.mojo;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codemucker.jfind.DirectoryRoot;
import org.codemucker.jfind.Root;
import org.codemucker.jfind.Root.RootContentType;
import org.codemucker.jfind.Root.RootType;
import org.codemucker.jfind.Roots;
import org.codemucker.jfind.matcher.ARoot;
import org.codemucker.jmutate.generate.GeneratorRunner;
import org.codemucker.jmutate.generate.GeneratorRunner.Builder;
import org.codemucker.jtest.MavenLayoutProjectResolver;
import org.codemucker.jtest.ProjectResolver;

@Mojo(name = "generate",defaultPhase=LifecyclePhase.GENERATE_SOURCES,requiresDependencyResolution=ResolutionScope.COMPILE_PLUS_RUNTIME)
//@Execute(phase = LifecyclePhase.GENERATE_SOURCES)
public class GeneratorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly=true, required=true)
    private MavenProject project;
    /**
     * The packages to scan for generation annotations
     */
    @Parameter(property = "jmutate.packages", defaultValue = "*",required=false)
    private String packages;

    @Parameter(property = "jmutate.output.dir", defaultValue = "src/generated/java",required=false)
    private String outputDir;

    @Parameter(property = "jmutate.src.dir", defaultValue = "**",required=false)
    private String scanDir;

    @Parameter(property = "jmutate.log.level", defaultValue = "info",required=false)
    private String logLevel;

    @Parameter(property = "jmutate.failonerror", defaultValue = "true",required=false)
    private boolean failOnError;

    /**
     * Custom annotation to generator bindings
     */
    @Parameter(property = "jmutate.generators",required=false)
    private Map<String, String> generators = new HashMap<String, String>();
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Running JMutate Code Generator");

        //setup logging so that callers can see what's going on
        BasicConfigurator.configure();
        //Logger.getRootLogger().setLevel(Level.toLevel(logLevel));
        Logger.getLogger("org.codemucker.jmutate").setLevel(Level.toLevel(logLevel));
        Logger.getLogger("org.codemucker.jfind").setLevel(Level.toLevel(logLevel));
        
        ProjectResolver resolver = new MavenLayoutProjectResolver();

        List<Root> roots;
        try {
            roots = Roots.with()
                .allSrcDirs()
                .classpath(true)
                .allDirs()
                .rootsPaths(project.getCompileSourceRoots(),RootType.MAIN, RootContentType.SRC)
                .rootsPaths(project.getTestCompileSourceRoots(),RootType.MAIN, RootContentType.SRC)
                
                .rootsPaths(project.getCompileClasspathElements(),RootType.DEPENDENCY, RootContentType.BINARY)
                .rootsPaths(project.getTestClasspathElements(),RootType.DEPENDENCY, RootContentType.BINARY)
                
                .build();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Error calculating roots",e);
        }

        Builder builder = GeneratorRunner.with()
                .defaults()
                .sources(roots)
                .scanRootMatching(ARoot.with().pathMatchingAntPattern(scanDir).isDirectory())
                .scanPackages(packages)
                .failOnParseError(failOnError)
                .defaultGenerateTo(new DirectoryRoot(new File(resolver.getBaseDir(), outputDir)));
        
        for(Map.Entry<String, String> entry:generators.entrySet()){
            builder.registerGenerator(entry.getKey(), entry.getValue());
        }
        builder.build().run();
    }
}

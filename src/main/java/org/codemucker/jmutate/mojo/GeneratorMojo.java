package org.codemucker.jmutate.mojo;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Execute;
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
import org.codemucker.jtest.MavenProjectLayout;
import org.codemucker.jtest.ProjectLayout;

@Mojo(name = "generate",defaultPhase=LifecyclePhase.GENERATE_SOURCES,requiresDependencyResolution=ResolutionScope.COMPILE_PLUS_RUNTIME)
@Execute( phase = LifecyclePhase.GENERATE_SOURCES )
public class GeneratorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly=true, required=true)
    private MavenProject project;
    
    private PluginDescriptor descriptor;
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

        ProjectLayout layout = new MavenProjectLayout();

        Set<URL> urls = new LinkedHashSet<>();
        extractProjectArtifacts(urls);
        
        List<Root> roots;
        try {
            roots = Roots.with()
                .projectLayout(layout)
                .all()
                .classpath(true)
                .urls(urls)
                .rootsPaths(project.getCompileSourceRoots(),RootType.MAIN, RootContentType.SRC)
                .rootsPaths(project.getTestCompileSourceRoots(),RootType.MAIN, RootContentType.SRC)
                .rootsPaths(project.getCompileClasspathElements(),RootType.DEPENDENCY, RootContentType.BINARY)
                .rootsPaths(project.getTestClasspathElements(),RootType.DEPENDENCY, RootContentType.BINARY)
                .build();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Error calculating roots",e);
        }

        
        List<Root> scanRoots;
        //try {
            scanRoots = Roots.with()
                .projectLayout(layout)
                .srcDirsOnly()
                .rootsPaths(project.getCompileSourceRoots(),RootType.MAIN, RootContentType.SRC)
                .rootsPaths(project.getTestCompileSourceRoots(),RootType.MAIN, RootContentType.SRC)
                
                //.rootsPaths(project.getCompileClasspathElements(),RootType.DEPENDENCY, RootContentType.BINARY)
                //.rootsPaths(project.getTestClasspathElements(),RootType.DEPENDENCY, RootContentType.BINARY)
                
                .build();
//        } catch (DependencyResolutionRequiredException e) {
//            throw new MojoExecutionException("Error calculating roots",e);
//        }

        
//        ClassLoader pluginClassLoader = getClass().getClassLoader();
//        ClassLoader generatorClassLoader = new URLClassLoader(urls,pluginClassLoader);

        Builder builder = GeneratorRunner.with()
                .defaults()
                .roots(roots)
                .scanRoots(scanRoots)
                .scanRootMatching(ARoot.that().isDirectory().pathMatchingAntPattern(scanDir))
                .scanPackages(packages)
                .failOnParseError(failOnError)
                .defaultGenerateTo(new DirectoryRoot(new File(layout.getBaseDir(), outputDir)));
        
        for(Map.Entry<String, String> entry:generators.entrySet()){
            builder.registerGenerator(entry.getKey(), entry.getValue());
        }
        builder.build().run();
    }

    private void extractProjectArtifacts(Set<URL> urls) {
        Artifact[] artifacts = project.getArtifacts().toArray(new Artifact[]{});
        for(int i = 0;i < artifacts.length;i++){
            Artifact art = artifacts[i];
            try {
                urls.add(art.getFile().toURI().toURL());
            } catch (MalformedURLException e) {
                //should never happen
                e.printStackTrace();
            }
        }
    }
}

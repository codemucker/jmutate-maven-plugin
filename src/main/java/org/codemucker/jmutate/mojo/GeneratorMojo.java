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
import org.codemucker.jmatch.AString;
import org.codemucker.jmatch.Matcher;
import org.codemucker.jmutate.DefaultProjectOptions;
import org.codemucker.jmutate.ProjectOptions;
import org.codemucker.jmutate.generate.GeneratorRunner;
import org.codemucker.jmutate.generate.GeneratorRunner.Builder;
import org.codemucker.jpattern.generate.ClashStrategy;
import org.codemucker.jtest.MavenProjectLayout;
import org.codemucker.jtest.ProjectLayout;

import com.google.common.base.Strings;

/**
 * Scans sources for generator annotations and invokes the appropriate generator
 */
@Mojo(name = "generate",defaultPhase=LifecyclePhase.GENERATE_SOURCES,requiresDependencyResolution=ResolutionScope.COMPILE_PLUS_RUNTIME)
@Execute( phase = LifecyclePhase.GENERATE_SOURCES )
public class GeneratorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly=true, required=true)
    private MavenProject project;
    
    private PluginDescriptor descriptor;
    /**
     * The packages to scan for generation annotations. Default is all packages
     */
    @Parameter(property = "jmutate.packages", defaultValue = "*",required=false)
    private String packages;

    /**
     * For generation which creates new source files (not just modifying your existing source), what root
     * directory to use.
     */
    @Parameter(property = "jmutate.output.dir", defaultValue = "src/generated/java",required=false)
    private String outputDir;

    /**
     * The source directory to scan for generation annotations. Default is all source directories (includes
     * test code)
     */
    @Parameter(property = "jmutate.src.dir", defaultValue = "**",required=false)
    private String scanDir;

    @Parameter(property = "jmutate.java.src.version", defaultValue = "",required=false)
    private String javaSourceVersion;

    @Parameter(property = "jmutate.java.target.version", defaultValue = "",required=false)
    private String javaTargetVersion;

    @Parameter(property = "jmutate.log.level", defaultValue = "info",required=false)
    private String logLevel;

    @Parameter(property = "jmutate.fail_on_parse_error", defaultValue = "true",required=false)
    private boolean failOnParseError;

    @Parameter(property = "jmutate.default.clash_strategy", defaultValue = "SKIP",required=false)
    private ClashStrategy defaultClashStrategy;

    /**
     * If true then don't run the code generator
     */
    @Parameter(property = "jmutate.skip", defaultValue = "false",required=false)
    private boolean skip;

    
    /**
     * Optionally restrict the generate annotations processed. Matches on the annotations full name. An
     * expression. Default is all
     * 
     * <p>E.g
     * 
     * <ul>
     * 	<li>(*GenerateBean) && !(*Builder || *Broken*)</li>
     * 	<li>com.acme.GenerateMyThing</li>
     * </ul>
     * </p>
     */
    @Parameter(property = "jmutate.annotation.matches", defaultValue = "*",required=false)
    private String annotationMatches;

    /**
     * Optionally restrict the generators to allow to be invoked. Matches on the generators full name. An
     * expression.Default is all
     * 
     * <p>E.g
     * 
     * <ul>
     * 	<li>(*.Bean*) && !(*Builder* || *Broken*)</li>
     * 	<li>com.acme.MyGenerator</li>
     * </ul>
     * </p>
     */
    @Parameter(property = "jmutate.generator.matches", defaultValue = "*",required=false)
    private String generatorMatches;

    /**
     * Custom annotation to generator bindings
     */
    @Parameter(property = "jmutate.generators",required=false)
    private Map<String, String> generators = new HashMap<String, String>();
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
    	if(skip){
            getLog().info("skipping code generation as jmutate.skip=true (set in pom or via system args)");
            return;
    	}
        getLog().info("Running JMutate Code Generator");
        getLog().info("  - to disable set jmutate.skip=true (in pom or via system args -Djmutate.skip)");
        getLog().info("  - for log level set jmutate.log.level=<level> (in pom or via system args -Djmutate.log.level=DEBUG)");

        //setup logging so that callers can see what's going on
        BasicConfigurator.configure();
        //Logger.getRootLogger().setLevel(Level.toLevel(logLevel));
        Logger.getLogger("org.codemucker.jmutate").setLevel(Level.toLevel(logLevel));
        Logger.getLogger("org.codemucker.jfind").setLevel(Level.toLevel(logLevel));

        ProjectLayout layout = new MavenProjectLayout();

        Set<URL> urls = new LinkedHashSet<>();
        extractProjectArtifacts(urls);
        
        //used for resolution
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

        //what to scan
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

        Matcher<String> annotationMatcher = stringMatcher(annotationMatches);
        Matcher<String> generatorMatcher = stringMatcher(generatorMatches);
            
        Builder builder = GeneratorRunner.with()
                .defaults()
                .roots(roots)
                .scanRoots(scanRoots)
                .scanRootMatching(ARoot.that().isDirectory().pathMatchingAntPattern(scanDir))
                .scanPackages(packages)
                .failOnParseError(failOnParseError)
                .defaultClashStrategy(defaultClashStrategy)
                .projectOptions(getProjectOptions())
                .matchAnnotations(annotationMatcher)
                .matchGenerator(generatorMatcher)
                .defaultGenerateTo(new DirectoryRoot(new File(layout.getBaseDir(), outputDir)));
        
        for(Map.Entry<String, String> entry:generators.entrySet()){
            builder.registerGenerator(entry.getKey(), entry.getValue());
        }
        builder.build().run();
    }

    private ProjectOptions getProjectOptions(){
    	DefaultProjectOptions opts = new DefaultProjectOptions();
    	if(!Strings.isNullOrEmpty(javaSourceVersion)){
    		opts.setSourceVersion(javaSourceVersion);
    	}
    	if(!Strings.isNullOrEmpty(javaTargetVersion)){
    		opts.setTargetVersion(javaTargetVersion);
    	}
    	return opts;
    }
    
	private static Matcher<String> stringMatcher(String expression) {
		return Strings.isNullOrEmpty(expression) || "*".equals(expression) ? AString.equalToAnything():AString.matchingExpression(expression);
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

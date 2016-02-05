package org.daisy.pipeline.pax.exam;

import java.io.File;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import static java.util.Collections.sort;
import java.util.HashSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import org.apache.maven.repository.internal.DefaultServiceLocator;
import org.apache.maven.repository.internal.MavenRepositorySystemSession;
import org.apache.maven.wagon.providers.http.HttpWagon;
import org.apache.maven.wagon.Wagon;

import static org.ops4j.pax.exam.CoreOptions.bundle;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.systemPackage;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;
import org.ops4j.pax.exam.MavenUtils;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.AbstractProvisionOption;
import org.ops4j.pax.exam.options.CompositeOption;
import org.ops4j.pax.exam.options.MavenArtifactProvisionOption;
import org.ops4j.pax.exam.options.MavenUrlReference.VersionResolver;
import org.ops4j.pax.exam.options.SystemPackageOption;
import org.ops4j.pax.exam.options.SystemPropertyOption;
import org.ops4j.pax.exam.options.UrlProvisionOption;
import org.ops4j.pax.exam.util.PathUtils;

import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.collection.CollectRequest;
import org.sonatype.aether.connector.wagon.WagonProvider;
import org.sonatype.aether.connector.wagon.WagonRepositoryConnectorFactory;
import org.sonatype.aether.graph.Dependency;
import org.sonatype.aether.graph.DependencyNode;
import org.sonatype.aether.repository.LocalRepository;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.resolution.DependencyRequest;
import org.sonatype.aether.resolution.DependencyResolutionException;
import org.sonatype.aether.spi.connector.RepositoryConnectorFactory;
import org.sonatype.aether.util.artifact.DefaultArtifact;
import org.sonatype.aether.util.DefaultRepositorySystemSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class Options {
	
	private static final Logger logger = LoggerFactory.getLogger(Options.class);
	
	private static final File LOCAL_REPOSITORY = new File(System.getProperty("user.home"), ".m2/repository");
	
	public static SystemPropertyOption logbackConfigFile() {
		return systemProperty("logback.configurationFile").value("file:" + PathUtils.getBaseDir() + "/src/test/resources/logback.xml");
	}
	
	public static Option calabashConfigFile() {
		return composite(
			systemProperty("org.daisy.pipeline.xproc.configuration").value(PathUtils.getBaseDir() + "/src/test/resources/config-calabash.xml"),
			systemProperty("com.xmlcalabash.config.user").value("")
		);
	}
	
	public static SystemPackageOption domTraversalPackage() {
		return systemPackage("org.w3c.dom.traversal;uses:=\"org.w3c.dom\";version=\"0.0.0.1\"");
	}
	
	public static MavenBundle felixDeclarativeServices() {
		return mavenBundle().groupId("org.apache.felix").artifactId("org.apache.felix.scr").version("1.6.2");
	}
	
	public static Option spiflyBundles() {
		return composite(
			mavenBundle().groupId("org.ow2.asm").artifactId("asm-all").version("4.0"),
			mavenBundle().groupId("org.apache.aries").artifactId("org.apache.aries.util").version("1.0.0"),
			mavenBundle().groupId("org.apache.aries.spifly").artifactId("org.apache.aries.spifly.dynamic.bundle").version("1.0.0")
		);
	}
	
	public static MavenBundle logbackClassic() {
		return mavenBundle("ch.qos.logback:logback-classic:1.0.11");
	}
	
	public static MavenBundleOption xprocspec() {
		return mavenBundleComposite(
			mavenBundle("org.daisy.maven:xprocspec-runner:?"),
			mavenBundle("org.daisy.xprocspec:xprocspec:?")
		);
	}
	
	public static MavenBundle xspec() {
		return mavenBundle("org.daisy.maven:xspec-runner:?");
	}
	
	public static UrlProvisionOption thisBundle() {
		File classes = new File(PathUtils.getBaseDir() + "/target/classes");
		Manifest manifest;
		try {
			InputStream stream = new File(classes, "META-INF/MANIFEST.MF").toURI().toURL().openStream();
			try {
				manifest = new Manifest(stream); }
			finally {
				stream.close(); }}
		catch (IOException e) {
			throw new RuntimeException(e); }
		String components = manifest.getMainAttributes().getValue("Service-Component");
		if (components != null)
			for (String component : components.split(","))
				if (!(new File(classes, component)).exists())
					return bundle("reference:"
					              + (new File(PathUtils.getBaseDir() + "/target/")).listFiles(
					                  new FilenameFilter() {
					                      public boolean accept(File dir, String name) {
					                          return name.endsWith(".jar"); }}
						              )[0].toURI());
		return bundle("reference:" + classes.toURI());
	}
	
	public static MavenBundle pipelineModule(String artifactId) {
		return mavenBundle().groupId("org.daisy.pipeline.modules").artifactId(artifactId);
	}
	
	public static MavenBundle brailleModule(String artifactId) {
		return mavenBundle().groupId("org.daisy.pipeline.modules.braille").artifactId(artifactId);
	}
	
	public static MavenBundle mavenBundle() {
		return new MavenBundle();
	}
	
	/**
	 * @param artifactCoords must be a string of the form
	 *    <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>. The default <extension> is
	 *    "jar". When <version> is "?", the version as declared in the project is used.
	 */
	public static MavenBundle mavenBundle(String artifactCoords) {
		return new MavenBundle(artifactFromCoords(artifactCoords));
	}
	
	public static interface MavenBundleOption extends Option {
		public MavenBundle[] getBundles();
	}
	
	public static class MavenBundle extends AbstractProvisionOption<MavenBundle> implements MavenBundleOption {
		
		private MavenBundle() {}
		
		private MavenBundle(Artifact artifact) {
			groupId(artifact.getGroupId());
			artifactId(artifact.getArtifactId());
			type(artifact.getExtension());
			classifier(artifact.getClassifier());
			version(artifact.getVersion());
		}
		
		private String url = null;
		
		public String getURL() {
			if (url == null) {
				MavenArtifactProvisionOption bundle = new MavenArtifactProvisionOption();
				bundle.groupId(groupId);
				bundle.artifactId(artifactId);
				if (type != null)
					bundle.type(type);
				if (classifier != null && !"".equals(classifier))
					bundle.classifier(classifier);
				if (version == null || version.equals("?"))
					version(versionAsInProject.getVersion(groupId, artifactId));
				bundle.version(version);
				// special handling of xprocspec
				if (groupId.equals("org.daisy.xprocspec") && artifactId.equals("xprocspec"))
					url = wrappedBundle(bundle)
						.bundleSymbolicName("org.daisy.xprocspec")
						.bundleVersion(version.replaceAll("-","."))
						.getURL();
				else
					url = bundle.getURL(); }
			return url;
		}
		
		public MavenBundle[] getBundles() {
			return new MavenBundle[]{this};
		}
			
		public MavenBundle itself() {
			return this;
		}
		
		private String groupId = null;
		private String artifactId = null;
		private String type = "jar";
		private String classifier = null;
		private String version = null;
		
		public MavenBundle groupId(String groupId) {
			checkURLResolved();
			this.groupId = groupId;
			return this;
		}
		
		public MavenBundle artifactId(String artifactId) {
			checkURLResolved();
			this.artifactId = artifactId;
			return this;
		}
		
		public MavenBundle type(String type) {
			checkURLResolved();
			this.type = type;
			return this;
		}
		
		public MavenBundle classifier(String classifier) {
			checkURLResolved();
			this.classifier = classifier;
			return this;
		}
		
		public MavenBundle forThisPlatform() {
			return classifier(thisPlatform());
		}
		
		public MavenBundle version(String version) {
			checkURLResolved();
			this.version = version;
			return this;
		}
		
		private Artifact asArtifact() {
			getURL();
			return new DefaultArtifact(groupId, artifactId, classifier, type, version);
		}
		
		private void checkURLResolved() {
			if (url != null)
				throw new RuntimeException();
		}
		
		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("mavenBundle(\"").append(artifactCoords(asArtifact())).append("\")");
			return sb.toString();
		}
	}
	
	private static MavenBundleOption mavenBundleComposite(final MavenBundleOption... options) {
		final MavenBundle[] bundles; {
			List<MavenBundle> list = new ArrayList<MavenBundle>();
			for (MavenBundleOption o : options)
				for (MavenBundle b : o.getBundles())
					list.add(b);
			bundles = list.toArray(new MavenBundle[list.size()]); }
		return new MavenBundleCompositeOption() {
			public MavenBundle[] getBundles() {
				return bundles;
			}
			@Override
			public String toString() {
				StringBuilder sb = new StringBuilder();
				sb.append("compositeMavenBundleOption(");
				int i = 0;
				for (MavenBundleOption b : bundles) {
					if (i > 0) sb.append(",");
					sb.append("\n	").append(b);
					i++; }
				sb.append(")");
				return sb.toString();
			}
		};
	}
	
	private static abstract class MavenBundleCompositeOption implements MavenBundleOption, CompositeOption {
		public MavenBundle[] getOptions() {
			return getBundles();
		}
	}
	
	public static MavenBundleOption mavenBundlesWithDependencies(MavenBundleOption... options) {
		return new MavenBundlesWithDependencies(options);
	}
	
	private static class MavenBundlesWithDependencies extends MavenBundleCompositeOption {
		
		private final List<MavenBundle> fromBundles;
		
		private MavenBundlesWithDependencies(MavenBundleOption... options) {
			fromBundles = new ArrayList<MavenBundle>();
			for (MavenBundleOption o : options)
				for (MavenBundle b : o.getBundles())
					fromBundles.add(b);
			logger.info(this.toString());
			StringBuilder sb = new StringBuilder();
			List<String> bundlesAsStrings = new ArrayList<String>();
			for (MavenBundle b : getBundles())
				bundlesAsStrings.add(b.toString());
			sort(bundlesAsStrings);
			sb.append("resolved to: MavenBundle[]{");
			int i = 0;
			for (String b : bundlesAsStrings) {
				if (i > 0) sb.append(",");
				sb.append("\n	").append(b);
				i++; }
			sb.append("}");
			logger.info(sb.toString());
		}
		
		private MavenBundle[] bundles = null;
		
		public MavenBundle[] getBundles() {
			if (bundles == null) {
				CollectRequest request = new CollectRequest();
				for (MavenBundle bundle : fromBundles) {
					request.addDependency(new Dependency(bundle.asArtifact(), "compile")); }
				RemoteRepository central = new RemoteRepository("central", "default", "http://repo1.maven.org/maven2/");
				request.addRepository(central);
				request.setRequestContext("runtime");
				DefaultServiceLocator locator = new DefaultServiceLocator();
				locator.addService(WagonProvider.class, HttpWagonProvider.class);
				locator.addService(RepositoryConnectorFactory.class, WagonRepositoryConnectorFactory.class);
				RepositorySystem system = locator.getService(RepositorySystem.class);
				DefaultRepositorySystemSession session = new MavenRepositorySystemSession()
					.setLocalRepositoryManager(
						system.newLocalRepositoryManager(
							new LocalRepository(LOCAL_REPOSITORY.getAbsolutePath())))
					.setOffline(false);
				List<RemoteRepository> repositories = new Vector<RemoteRepository>();
				repositories.add(central);
				Set<Artifact> deps = new HashSet<Artifact>();
				try {
					addDependencies(deps, system.resolveDependencies(session, new DependencyRequest().setCollectRequest(request)).getRoot()); }
				catch (DependencyResolutionException e) {
					throw new RuntimeException(e); }
				List<MavenBundle> list = new ArrayList<MavenBundle>();
				for (Artifact dep : deps)
					try {
						String groupId = dep.getGroupId();
						String artifactId = dep.getArtifactId();
						String classifier = dep.getClassifier();
						if (// these should not be runtime dependencies -> fix in POMs
							!(groupId.equals("org.osgi") && (artifactId.equals("org.osgi.compendium") || artifactId.equals("org.osgi.core")))
							// fragment bundles not supported
							&& !(groupId.equals("org.slf4j") && artifactId.equals("slf4j-jdk14"))
							) {
							if ((classifier.equals("linux") || classifier.equals("mac") || classifier.equals("windows")))
								if (!classifier.equals(thisPlatform()))
									continue;
							if (!(groupId.equals("org.daisy.xprocspec") && artifactId.equals("xprocspec")))
								validateBundle(dep.getFile());
							list.add(new MavenBundle(dep)); }}
					catch(Exception e) {}
				bundles = list.toArray(new MavenBundle[list.size()]);
			}
			return bundles;
		}
		
		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("mavenBundlesWithDependencies(");
			int i = 0;
			for (MavenBundle b : fromBundles) {
				if (i > 0) sb.append(",");
				sb.append("\n	").append(b);
				i++; }
			sb.append(")");
			return sb.toString();
		}
		
		private static void addDependencies(Set<Artifact> deps, DependencyNode node) {
			Dependency dep = node.getDependency();
			if (dep != null)
				deps.add(dep.getArtifact());
			for (DependencyNode n : node.getChildren())
				addDependencies(deps, n);
		}
		
		private static void validateBundle(File bundle) {
			JarFile jar = null;
			try {
				jar = new JarFile(bundle, false);
				Manifest manifest = jar.getManifest();
				if (manifest == null)
					throw new RuntimeException("[" + bundle + "] is not a valid bundle: manifest is missing");
				String bundleSymbolicName = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
				String bundleName = manifest.getMainAttributes().getValue("Bundle-Name");
				if (bundleSymbolicName == null && bundleName == null) {
					throw new RuntimeException("[" + bundle + "] is not a valid bundle: Bundle-SymbolicName and Bundle-Name are missing"); }}
			catch (IOException e) {
				throw new RuntimeException("[" + bundle + "] is not a valid bundle: failed reading jar", e); }
			finally {
				if (jar != null)
					try {
						jar.close(); }
					catch (IOException e) {}}
		}
		
		public static class HttpWagonProvider implements WagonProvider {
			public Wagon lookup(String roleHint) throws Exception {
				if ("http".equals(roleHint) || "https".equals(roleHint))
					return new HttpWagon();
				return null;
			}
			public void release(Wagon wagon) {}
		}
	}
	
	private static final VersionResolver versionAsInProject = MavenUtils.asInProject();
	
	private static Artifact artifactFromCoords(String coords) {
		return new DefaultArtifact(coords);
	}
	
	private static String artifactCoords(Artifact artifact) {
		String groupId = artifact.getGroupId();
		String artifactId = artifact.getArtifactId();
		String extension = artifact.getExtension();
		String classifier = artifact.getClassifier();
		String version = artifact.getVersion();
		StringBuilder b = new StringBuilder()
			.append(groupId).append(":")
			.append(artifactId).append(":");
		if (!extension.equals("jar") || !classifier.equals("")) {
			b.append(extension).append(":");
			if (!classifier.equals(""))
				b.append(classifier).append(":"); }
		b.append(version);
		return b.toString();
	}
	
	private static String thisPlatform() {
		String name = System.getProperty("os.name").toLowerCase();
		if (name.startsWith("windows"))
			return "windows";
		else if (name.startsWith("mac os x"))
			return "mac";
		else if (name.startsWith("linux"))
			return "linux";
		else
			throw new RuntimeException("Unsupported OS: " + name);
	}
}

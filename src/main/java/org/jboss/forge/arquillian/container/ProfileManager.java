package org.jboss.forge.arquillian.container;

import org.apache.maven.model.Activation;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jboss.forge.addon.dependencies.Dependency;
import org.jboss.forge.addon.dependencies.builder.DependencyBuilder;
import org.jboss.forge.addon.maven.dependencies.MavenDependencyAdapter;
import org.jboss.forge.addon.maven.plugins.ConfigurationBuilder;
import org.jboss.forge.addon.maven.plugins.ExecutionBuilder;
import org.jboss.forge.addon.maven.plugins.MavenPluginAdapter;
import org.jboss.forge.addon.maven.plugins.MavenPluginBuilder;
import org.jboss.forge.addon.maven.projects.MavenFacet;
import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.arquillian.container.model.Container;

import javax.inject.Inject;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProfileManager {

    @Inject
    private ContainerResolver containerResolver;

    public List<String> getArquillianProfiles(Project project) {
        MavenFacet mavenCoreFacet = project.getFacet(MavenFacet.class);
        List<String> profiles = new ArrayList<>();
        List<Profile> profileList = mavenCoreFacet.getModel().getProfiles();
        for (Profile profile : profileList) {
            profiles.add(profile.getId());
        }
        Collections.sort(profiles);
        return profiles;
    }

    public boolean isAnyProfileRegistered(Project project) {
        MavenFacet mavenCoreFacet = project.getFacet(MavenFacet.class);
        return !mavenCoreFacet.getModel().getProfiles().isEmpty();
    }

    public void addProfile(Project project, Container container, boolean activatedByDefault, List<Dependency> dependencies) {
        Dependency[] deps = new Dependency[dependencies.size()];
        addProfile(project, container, activatedByDefault, dependencies.toArray(deps));
    }

    public void addProfile(Project project, Container container, String chameleonTargetVersion, boolean activatedByDefault) {
        MavenFacet facet = project.getFacet(MavenFacet.class);

        Profile profile = createProfile(container, activatedByDefault);

        addBuildBaseToProfile(profile, container, chameleonTargetVersion);

        Model pom = checkForExistingProfileAndGetPom(facet, container, profile);
        facet.setModel(pom);
    }

    public void addProfile(Project project, Container container, boolean activatedByDefault, Dependency... dependencies) {
        MavenFacet facet = project.getFacet(MavenFacet.class);

        Profile profile = createProfile(container, activatedByDefault);

        addBuildBaseToProfile(profile, container, null);
        addDependencyToProfile(profile, dependencies);

        Model pom = checkForExistingProfileAndGetPom(facet, container, profile);
        facet.setModel(pom);
    }

    public void addContainerConfiguration(Container container, Project project, String version) {
        MavenFacet mavenCoreFacet = project.getFacet(MavenFacet.class);
        Model pom = mavenCoreFacet.getModel();

        Profile containerProfile = findProfileById(container.getProfileId(), pom);
        if (containerProfile == null) {
            containerProfile = findProfileById(container.getId(), pom);
        }
        if (containerProfile == null) {
            throw new RuntimeException("Container profile with id " + container.getId() + " or "
                + container.getProfileId() + " not found");
        }

        MavenPluginBuilder pluginBuilder = MavenPluginBuilder.create();
        String downloadUrl = container.getDownload().getUrl();

        if (downloadUrl != null) {
            ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create();
            configurationBuilder.createConfigurationElement("url").setText(downloadUrl);
            configurationBuilder.createConfigurationElement("unpack").setText("true");
            configurationBuilder.createConfigurationElement("overwrite").setText("false");
            configurationBuilder.createConfigurationElement("outputDirectory").setText("${project.basedir}/target/");

            pluginBuilder
                .setCoordinate(DependencyBuilder.create("com.googlecode.maven-download-plugin:download-maven-plugin").getCoordinate())
                .addExecution(getExecutionBuilderWithConfiguration(configurationBuilder, "wget"));
        } else if (container.getDownload().getArtifactId() != null && container.getDownload().getGroupId() != null) {
            ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create();
            configurationBuilder.createConfigurationElement("artifactItems")
                .createConfigurationElement("artifactItem")
                .addChild("groupId").setText(container.getDownload().getGroupId()).getParentElement()
                .addChild("artifactId").setText(container.getDownload().getArtifactId()).getParentElement()
                .addChild("version").setText(version).getParentElement()
                .addChild("type").setText("zip").getParentElement()
                .addChild("overWrite").setText("false").getParentElement()
                .addChild("outputDirectory")
                .setText("${project.basedir}/target/");

            pluginBuilder
                .setCoordinate(DependencyBuilder.create("org.apache.maven.plugins:maven-dependency-plugin").getCoordinate())
                .addExecution(getExecutionBuilderWithConfiguration(configurationBuilder, "unpack"));
        }

        BuildBase build = containerProfile.getBuild();
        if (build == null) {
            build = new BuildBase();
        }

        build.addPlugin(new MavenPluginAdapter(pluginBuilder));
        containerProfile.setBuild(build);
        pom.removeProfile(containerProfile);
        pom.addProfile(containerProfile);

        mavenCoreFacet.setModel(pom);
    }

    private ExecutionBuilder getExecutionBuilderWithConfiguration(org.jboss.forge.addon.maven.plugins.Configuration configuration, String goal) {
        return ExecutionBuilder.create().setId("unpack").setPhase("process-test-classes").addGoal(goal)
            .setConfig(configuration);
    }

    private Model checkForExistingProfileAndGetPom(MavenFacet facet, Container container, Profile profile) {
        Model pom = facet.getModel();

        Profile existingProfile = findProfileById(container.getProfileId(), pom);

        if (existingProfile != null) {
            // preserve existing id
            profile.setId(existingProfile.getId());
            pom.removeProfile(existingProfile);
        }

        pom.addProfile(profile);

        return pom;
    }

    private void addBuildBaseToProfile(Profile profile, Container container, String containerVersion) {
        BuildBase buildBase = new BuildBase();

        Plugin surefirePlugin = createSurefirePlugin();
        String profileId = container.getProfileId();

        if (containerVersion != null) {
            surefirePlugin.setConfiguration(buildConfiguration(profileId, container.getChameleonTarget(containerVersion)));
        } else {
            surefirePlugin.setConfiguration(buildConfiguration(profileId));
        }

        buildBase.addPlugin(surefirePlugin);

        profile.setBuild(buildBase);
    }

    private void addDependencyToProfile(Profile profile, Dependency... dependencies) {
        for (Dependency dependency : dependencies) {
            profile.addDependency(new MavenDependencyAdapter(DependencyBuilder.create(dependency)));
        }
    }

    private Profile createProfile(Container container, boolean activatedByDefault) {
        Profile profile = new Profile();
        profile.setId(container.getProfileId());

        if (activatedByDefault) {
            Activation activation = new Activation();
            activation.setActiveByDefault(true);
            profile.setActivation(activation);
        }

        return profile;
    }

    private Plugin createSurefirePlugin() {
        Plugin surefirePlugin = new Plugin();
        surefirePlugin.setArtifactId("maven-surefire-plugin");
        surefirePlugin.setVersion("2.14.1");

        return surefirePlugin;

    }

    public Container getContainer(String profile) {
        String profileId = profile.replaceFirst("^arq-", "arquillian-");
        for (Container container : containerResolver.getContainers()) {
            if (container.getProfileId().equals(profileId)) {
                return container;
            }
        }
        throw new RuntimeException("Container not found for profile " + profile);
    }

    private Profile findProfileById(String profileId, Model pom) {
        for (Profile profile : pom.getProfiles()) {
            if (profileId.equalsIgnoreCase(profile.getId().replaceFirst("^arq-", "arquillian-"))) {
                return profile;
            }
        }
        return null;
    }

    /*
     * Create the surefire plugin configuration, so we call the relevant Arquillian container config
     *
     * <plugin> <artifactId>maven-surefire-plugin</artifactId> <configuration> <systemPropertyVariables>
     * <arquillian.launch>${profileId}</arquillian.launch> </systemPropertyVariables> </configuration> </plugin>
     */
    private Object buildConfiguration(String profileId) {
        try {
            return Xpp3DomBuilder.build(new StringReader(
                "<configuration>\n" +
                    "    <systemPropertyVariables>\n" +
                    "        <arquillian.launch>" + profileId + "</arquillian.launch>\n" +
                    "    </systemPropertyVariables>\n" +
                    "</configuration>"));
        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /*
 * Create the surefire plugin configuration, so we call the relevant Arquillian container config
 *
 * <plugin> <artifactId>maven-surefire-plugin</artifactId> <configuration> <systemPropertyVariables>
 * <arquillian.launch>${profileId}</arquillian.launch> <chameleon.target> ${chameleonTarget}</chameleon.target></systemPropertyVariables> </configuration> </plugin>
 */
    private Object buildConfiguration(String profileId, String chameleonTarget) {
        try {
            return Xpp3DomBuilder.build(new StringReader(
                "<configuration>\n" +
                    "    <systemPropertyVariables>\n" +
                    "        <arquillian.launch>" + profileId + "</arquillian.launch>\n" +
                    "        <chameleon.target>" + chameleonTarget + "</chameleon.target>\n" +
                    "    </systemPropertyVariables>\n" +
                    "</configuration>"));
        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

package io.github.kaluchi.jdtbridge;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;

/**
 * Creates a test Java project with known classes for integration tests.
 *
 * <pre>
 * test.model.Animal      — interface with name()
 * test.model.Dog          — implements Animal, has bark()
 * test.model.Cat          — implements Animal
 * test.service.AnimalService — uses Dog.bark(), Animal.name()
 * test.broken.BrokenClass — intentional compilation error
 * </pre>
 */
class TestFixture {

    static final String PROJECT_NAME = "jdtbridge-test";

    private static final String ANIMAL_SRC = """
            package test.model;

            public interface Animal {
                String name();
            }
            """;

    private static final String DOG_SRC = """
            package test.model;

            public class Dog implements Animal {
                private int age;

                @Override
                public String name() {
                    return "Dog";
                }

                public void bark() {
                    System.out.println("Woof!");
                }
            }
            """;

    private static final String CAT_SRC = """
            package test.model;

            public class Cat implements Animal {
                @Override
                public String name() {
                    return "Cat";
                }
            }
            """;

    private static final String SERVICE_SRC = """
            package test.service;

            import test.model.Animal;
            import test.model.Dog;

            public class AnimalService {
                public void process(Animal animal) {
                    animal.name();
                }

                public Dog createDog() {
                    Dog d = new Dog();
                    d.bark();
                    return d;
                }
            }
            """;

    private static final String BROKEN_SRC = """
            package test.broken;

            public class BrokenClass {
                UnknownType x;
            }
            """;

    static void create() throws Exception {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject(PROJECT_NAME);

        if (project.exists()) {
            project.delete(true, true, null);
        }

        project.create(null);
        project.open(null);

        // Add Java nature
        IProjectDescription desc = project.getDescription();
        desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.setDescription(desc, null);

        IJavaProject javaProject = JavaCore.create(project);

        // Create source folder
        IFolder srcFolder = project.getFolder("src");
        srcFolder.create(true, true, null);

        // Set classpath: src + JRE
        IClasspathEntry srcEntry =
                JavaCore.newSourceEntry(srcFolder.getFullPath());
        IClasspathEntry jreEntry = JavaCore.newContainerEntry(
                new Path("org.eclipse.jdt.launching.JRE_CONTAINER"));
        javaProject.setRawClasspath(
                new IClasspathEntry[] { srcEntry, jreEntry }, null);

        // Create packages and source files
        IPackageFragmentRoot srcRoot =
                javaProject.getPackageFragmentRoot(srcFolder);

        IPackageFragment modelPkg =
                srcRoot.createPackageFragment("test.model", true, null);
        modelPkg.createCompilationUnit(
                "Animal.java", ANIMAL_SRC, true, null);
        modelPkg.createCompilationUnit("Dog.java", DOG_SRC, true, null);
        modelPkg.createCompilationUnit("Cat.java", CAT_SRC, true, null);

        IPackageFragment servicePkg =
                srcRoot.createPackageFragment("test.service", true, null);
        servicePkg.createCompilationUnit(
                "AnimalService.java", SERVICE_SRC, true, null);

        IPackageFragment brokenPkg =
                srcRoot.createPackageFragment("test.broken", true, null);
        brokenPkg.createCompilationUnit(
                "BrokenClass.java", BROKEN_SRC, true, null);

        // Wait for auto-build to finish
        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, null);
    }

    static void destroy() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot()
                .getProject(PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, null);
        }
    }
}

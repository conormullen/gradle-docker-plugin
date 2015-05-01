/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bmuschko.gradle.docker

import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerInspectImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage
import com.bmuschko.gradle.docker.tasks.image.DockerRemoveImage
import com.bmuschko.gradle.docker.tasks.image.Dockerfile
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.Copy
import org.gradle.internal.reflect.Instantiator

/**
 * Opinonated Gradle plugin for creating a Docker Image.
 */
class DockerBuildTypePlugin implements Plugin<Project> {

    static final String EXTENSION_NAME = 'dockerBuild'
    private static final String MAIN_DISTRIBUTION_NAME = "main"
    private static final String DOCKER_HUB = "docker.io/v1"


    @Override
    void apply(Project project) {
        project.apply(plugin: DockerRemoteApiPlugin)
        project.extensions.create(EXTENSION_NAME, DockerBuildTypeExtension)

        // Create a DockerImageBuild for the entries within the dockerBuild extension.
        project.dockerBuild.extensions.images = project.container(DockerImageBuild) {String name ->
            DockerImageBuild repo = project.gradle.services.get(Instantiator).newInstance(DockerImageBuild, name)
            assert repo instanceof ExtensionAware
            return repo
        }

        applyTasks(project)
    }

    void applyTasks(Project project){
        project.configurations {
            dockerLibs
        }

        project.task("copyToDockerLib", type: Copy) {
            into "${project.buildDir}/docker/libs"
            from project.configurations.dockerLibs
        }

        project.task("copyDockerSource",type: Copy) {
            from "${project.projectDir}/src/main/docker"
            into "${project.buildDir}/docker"
        }

        project.task("createDockerFile",dependsOn: ['copyToDockerLib', 'copyDockerSource'],type: Dockerfile) {
            destFile = project.file("${project.buildDir}/docker/Dockerfile")
        }

        project.afterEvaluate{
            project.dockerBuild.images.each { repo ->
                configureDefaultProperties(repo)
                setupDockerExtension(repo)

                project.task("inspectImage${repo.taskSuffix}",type: DockerInspectImage) {
                    imageId "${repo.location}/${repo.imageName}:${repo.imageTag}"
                }

                project.task("removeImage${repo.taskSuffix}", type: DockerRemoveImage) {
                    onlyIf { inspect project.tasks.inspectImage }
                    imageId "${repo.location}/${repo.imageName}:${repo.imageTag}"
                }

                project.clean.dependsOn("removeImage${repo.taskSuffix}")

                project.task("buildImage${repo.taskSuffix}", type: DockerBuildImage) {
                    dependsOn 'createDockerFile'
                    quiet false
                    inputDir project.createDockerFile.destFile.parentFile
                    tag "${repo.location}/${repo.imageName}:${repo.imageTag}"
                }

                project.task("publish${repo.taskSuffix}", type: DockerPushImage) {
                    dependsOn "buildImage${repo.taskSuffix}"
                    imageName "${repo.location}/${repo.imageName}"
                    tag "${repo.imageTag}"
                }
            }

        }
    }

    private void configureDefaultProperties(repo) {
        if (repo.name.equals(MAIN_DISTRIBUTION_NAME)) {
            if (repo.location == null || repo.location.isEmpty()) {
                repo.location = DOCKER_HUB
            }
        } else {
            repo.taskSuffix = repo.name.capitalize()
        }
    }

    private void setupDockerExtension(repo) {
        DockerExtension existingExtension = project.extensions.findByName("docker") as DockerExtension
        existingExtension.with {
            if (System.getenv("DOCKER_HOST") != null) {
                url = System.getenv("DOCKER_HOST").replaceFirst("tcp", System.getenv("DOCKER_TLS_VERIFY") == "1" ? "https" : "http")
                certPath = new File(System.getenv("DOCKER_CERT_PATH"))
            }
            registryCredentials {
                url = "https://${repo.location}"
            }
        }
    }

    def inspect(Task task) {
        try {
            task.execute()
            true
        } catch (e) {
            false
        }
    }
}


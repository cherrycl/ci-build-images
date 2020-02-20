def taggedAMD64Images
def taggedARM64Images

def buildCompose(config) {
    edgex.bannerMessage "[buildCompose] RAW Config: ${config}"

    edgeXBuildDocker.validate(config)
    edgex.setupNodes(config)

    def _envVarMap = edgeXBuildDocker.toEnvironment(config)

    ///////////////////////////////////////////////////////////////////////////

    pipeline {
        agent { label edgex.mainNode(config) }
        
        options {
            timestamps()
            preserveStashes()
            quietPeriod(5) // wait a few seconds before starting to aggregate builds...??
            durabilityHint 'PERFORMANCE_OPTIMIZED'
            timeout(360)
        }

        triggers {
            issueCommentTrigger('.*^recheck$.*')
        }

        stages {
            stage('Prepare') {
                steps {
                    edgeXSetupEnvironment(_envVarMap)
                }
            }

            stage('Semver Prep') {
                when { environment name: 'USE_SEMVER', value: 'true' }
                steps {
                    edgeXSemver 'init' // <-- Generates a VERSION file and .semver directory
                }
            }

            stage('Build Docker Image') {
                parallel {
                    stage('amd64') {
                        when {
                            beforeAgent true
                            expression { edgex.nodeExists(config, 'amd64') }
                        }
                        agent { label edgex.getNode(config, 'amd64') } // comment out to reuse mainNode
                        environment {
                            ARCH = 'x86_64'
                        }
                        stages {
                            stage('Prep') {
                                steps {
                                    script {
                                        if(env.USE_SEMVER == 'true') {
                                            unstash 'semver'
                                        }
                                    }
                                }
                            }
                            stage ('Checkout Compose Repository') {
                                checkout([$class: 'GitSCM',
                                    branches: [[name: "refs/tags/${DOCKER_CUSTOM_TAGS}"]],
                                    doGenerateSubmoduleConfigurations: false, 
                                    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: '']], 
                                    submoduleCfg: [], 
                                    userRemoteConfigs: [[url: 'https://github.com/docker/compose.git']]
                                ])
                            }

                            stage('Docker Build') {
                                steps {
                                    script {
                                        edgeXDocker.build("${DOCKER_IMAGE_NAME}")
                                    }
                                }
                            }

                            stage('Docker Push') {
                                when {
                                    allOf {
                                        environment name: 'PUSH_DOCKER_IMAGE', value: 'true'
                                        expression { edgex.isReleaseStream() || (env.GIT_BRANCH == env.RELEASE_BRANCH_OVERRIDE) }
                                    }
                                }

                                steps {
                                    script {
                                        edgeXDockerLogin(settingsFile: env.MAVEN_SETTINGS)
                                        taggedAMD64Images = edgeXDocker.push("${DOCKER_IMAGE_NAME}", true, "${DOCKER_NEXUS_REPO}")
                                    }
                                }
                            }

                            stage('Archive Image') {
                                when {
                                    environment name: 'ARCHIVE_IMAGE', value: 'true'
                                }
                                
                                steps {
                                    script {
                                        withEnv(["IMAGE=${edgeXDocker.finalImageName("${DOCKER_IMAGE_NAME}")}"]) {
                                            // save the docker image to tar file
                                            sh 'docker save -o $WORKSPACE/$(printf \'%s\' "${ARCHIVE_NAME%.tar.gz}-$ARCH.tar.gz") ${IMAGE}'
                                            archiveArtifacts allowEmptyArchive: true, artifacts: '*.tar.gz', fingerprint: true, onlyIfSuccessful: true
                                        }
                                    }
                                    
                                }
                            }
                        }
                    }

                    stage('arm64') {
                        when {
                            beforeAgent true
                            expression { edgex.nodeExists(config, 'arm64') }
                        }
                        agent { label edgex.getNode(config, 'arm64') }
                        environment {
                            ARCH = 'arm64'
                        }
                        stages {
                            stage('Prep') {
                                steps {
                                    script {
                                        if(env.USE_SEMVER == 'true') {
                                            unstash 'semver'
                                        }
                                    }
                                }
                            }

                            stage ('Checkout Compose Repository') {
                                checkout([$class: 'GitSCM',
                                    branches: [[name: "refs/tags/${DOCKER_CUSTOM_TAGS}"]],
                                    doGenerateSubmoduleConfigurations: false, 
                                    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: '']], 
                                    submoduleCfg: [], 
                                    userRemoteConfigs: [[url: 'https://github.com/docker/compose.git']]
                                ])
                            }

                            stage('Docker Build') {
                                steps {
                                    script {
                                        edgeXDocker.build("${DOCKER_IMAGE_NAME}-${ARCH}")
                                    }
                                }
                            }

                            stage('Docker Push') {
                                when {
                                    allOf {
                                        environment name: 'PUSH_DOCKER_IMAGE', value: 'true'
                                        expression { edgex.isReleaseStream() || (env.GIT_BRANCH == env.RELEASE_BRANCH_OVERRIDE) }
                                    }
                                }

                                steps {
                                    script {
                                        edgeXDockerLogin(settingsFile: env.MAVEN_SETTINGS)
                                        taggedARM64Images = edgeXDocker.push("${DOCKER_IMAGE_NAME}-${ARCH}", true, "${DOCKER_NEXUS_REPO}")
                                    }
                                }
                            }

                            stage('Archive Image') {
                                when {
                                    environment name: 'ARCHIVE_IMAGE', value: 'true'
                                }
                                
                                steps {
                                    script {
                                        withEnv(["IMAGE=${edgeXDocker.finalImageName("${DOCKER_IMAGE_NAME}-${ARCH}")}"]) {
                                            // save the docker image to tar file
                                            sh 'docker save -o $WORKSPACE/$(printf \'%s\' "${ARCHIVE_NAME%.tar.gz}-$ARCH.tar.gz") ${IMAGE}'
                                            archiveArtifacts allowEmptyArchive: true, artifacts: '*.tar.gz', fingerprint: true, onlyIfSuccessful: true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // When scanning the clair image, the FQDN is needed
            stage('Clair Scan') {
                when {
                    allOf {
                        environment name: 'PUSH_DOCKER_IMAGE', value: 'true'
                        expression { edgex.isReleaseStream() || (env.GIT_BRANCH == env.RELEASE_BRANCH_OVERRIDE) }
                    }
                }
                steps {
                    script {
                        if(edgex.nodeExists(config, 'amd64') && taggedAMD64Images) {
                            edgeXClair(taggedAMD64Images.first())
                        }
                        if(edgex.nodeExists(config, 'arm64') && taggedARM64Images) {
                            edgeXClair(taggedARM64Images.first())
                        }
                    }
                }
            }

            stage('Semver') {
                when {
                    allOf {
                        environment name: 'USE_SEMVER', value: 'true'
                        expression { edgex.isReleaseStream() || (env.GIT_BRANCH == env.RELEASE_BRANCH_OVERRIDE) }
                    }
                }
                stages {
                    stage('Tag') {
                        steps {
                            unstash 'semver'

                            edgeXSemver 'tag'
                            edgeXInfraLFToolsSign(command: 'git-tag', version: 'v${VERSION}')
                        }
                    }
                    stage('Bump Pre-Release Version') {
                        steps {
                            edgeXSemver "bump ${env.SEMVER_BUMP_LEVEL}"
                            edgeXSemver 'push'
                        }
                    }
                }
            }
        }

        post {
            failure {
                script { currentBuild.result = "FAILED" }
            }
            always {
                edgeXInfraPublish()
            }
            cleanup {
                cleanWs()
            }
        }
    }
}

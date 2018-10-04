pipeline {
  agent any
  stages {
    stage("Compile") {
      agent { label 'johannes' }
      environment {
        SBT_BIN = tool name: 'sbt-latest-deb', type: 'org.jvnet.hudson.plugins.SbtPluginBuilder$SbtInstallation'
      }
      steps {
        checkout scm
        withEnv(['PATH+EXTRA=/usr/sbin:/usr/bin:/sbin:/bin']) {
          ansiColor('xterm') {
            sh 'echo `pwd`'
            sh 'java -Xmx3g -jar ${SBT_BIN} -Dakka.genjavadoc.enabled=true -Dsbt.ivy.home=/localhome/jenkinsakka/.ivy2 -Dakka.build.M2Dir=/localhome/jenkinsakka/.m2/repository -Dakka.test.timefactor=2 -Dakka.cluster.assert=on -Dsbt.override.build.repos=false clean update compile'
          }

          stash includes: '**/target/**', name: 'build-results'
          deleteDir()
        }
      }
    }
    stage("Test") {
      parallel {
        stage("core tests") {
            agent { label 'johannes' }
            environment {
              SBT_BIN = tool name: 'sbt-latest-deb', type: 'org.jvnet.hudson.plugins.SbtPluginBuilder$SbtInstallation'
            }

            steps {
              unstash name: 'build-results'
              withEnv(['PATH+EXTRA=/usr/sbin:/usr/bin:/sbin:/bin']) {
                ansiColor('xterm') {
                  sh 'echo `pwd`'
                  sh 'java -Xmx3g -jar ${SBT_BIN} -Dakka.genjavadoc.enabled=true -Dsbt.ivy.home=/localhome/jenkinsakka/.ivy2 -Dakka.build.M2Dir=/localhome/jenkinsakka/.m2/repository -Dakka.test.timefactor=2 -Dakka.cluster.assert=on -Dsbt.override.build.repos=false akka-http-core/test:compile'
                }
                deleteDir()
              }
            }
        }
        stage("other tests") {
            agent { label 'johannes' }
            environment {
              SBT_BIN = tool name: 'sbt-latest-deb', type: 'org.jvnet.hudson.plugins.SbtPluginBuilder$SbtInstallation'
            }

            steps {
              unstash name: 'build-results'
              withEnv(['PATH+EXTRA=/usr/sbin:/usr/bin:/sbin:/bin']) {
                ansiColor('xterm') {
                  sh 'echo `pwd`'
                  sh 'java -Xmx3g -jar ${SBT_BIN} -Dakka.genjavadoc.enabled=true -Dsbt.ivy.home=/localhome/jenkinsakka/.ivy2 -Dakka.build.M2Dir=/localhome/jenkinsakka/.m2/repository -Dakka.test.timefactor=2 -Dakka.cluster.assert=on -Dsbt.override.build.repos=false akka-http-tests/test:compile'
                }
                deleteDir()
              }
            }
        }
      }
    }
  }
}
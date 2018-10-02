pipeline {
  agent {
    label 'johannes'
  }
  stages {
    stage("Compile") {
      steps {
        checkout scm
        withEnv(['PATH+EXTRA=/usr/sbin:/usr/bin:/sbin:/bin']) {
          sh 'sbt -Dakka.genjavadoc.enabled=true -Dsbt.ivy.home=/localhome/jenkinsakka/.ivy2 -Dakka.build.M2Dir=/localhome/jenkinsakka/.m2/repository -Dakka.test.timefactor=2 -Dakka.cluster.assert=on -Dsbt.override.build.repos=false clean update test:compile'
        }
      }
    }
  }
}
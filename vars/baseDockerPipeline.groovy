def call(Map config = [:]) {
  pipeline {
    agent {
      kubernetes {
        yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: docker
      image: amor573/jenkins-agent-docker
      command:
        - cat
      tty: true
      volumeMounts:
        - name: docker-sock
          mountPath: /var/run/docker.sock
  volumes:
    - name: docker-sock
      hostPath:
        path: /var/run/docker.sock
"""
      }
    }

    environment {
      IMAGE_TAG = "${env.BUILD_NUMBER}"
    }

    stages {
      stage('Checkout') {
        steps {
          checkout scm
        }
      }

      stage('Docker Login & Build') {
        steps {
          container('docker') {
            withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
              sh """
                echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                docker build -t ${config.imageName}:${IMAGE_TAG} .
                docker tag ${config.imageName}:${IMAGE_TAG} ${config.imageName}:latest
              """
            }
          }
        }
      }

      stage('Authenticate Snyk') {
        steps {
          container('docker') {
            withCredentials([string(credentialsId: 'synk-token', variable: 'SNYK_TOKEN')]) {
              sh 'snyk auth $SNYK_TOKEN'
            }
          }
        }
      }

      stage('Snyk Image Scan') {
        steps {
          container('docker') {
            sh "snyk container test ${config.imageName}:${IMAGE_TAG}"
          }
        }
      }

      stage('Push Image to Docker Hub') {
        steps {
          container('docker') {
            withCredentials([usernamePassword(credentialsId: 'dockerhub-creds', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
              sh """
                echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                docker push ${config.imageName}:${IMAGE_TAG}
                docker push ${config.imageName}:latest
              """
            }
          }
        }
      }

      stage('Optional Extra Steps') {
        when {
          expression { return config.extraSteps != null }
        }
        steps {
          script {
            config.extraSteps()
          }
        }
      }
    }
  }
}

return this

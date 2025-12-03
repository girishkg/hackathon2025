pipeline {
    agent any
    environment {
        MAVEN_OPTS = "-Xmx1024m"
        REPORT_DIR = "${env.WORKSPACE}/target/surefire-reports"
        REPORT_PDF = "${env.WORKSPACE}/target/test_report.pdf"
    }

    stages {
        // 1. Checkout
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        // 2. Build & Dependency Cache
        stage('Build') {
            steps {
                sh 'mvn -B clean compile'
            }
        }

        // 3. Predictive Test Selection (optional)
        stage('Predictive Test Selector') {
            when {
                branch 'main'
            }
            steps {
                sh '''
                    mvn -B package -DskipTests
                    java -cp target/my-app-1.0-SNAPSHOT.jar com.example.PredictiveSelector \
                        ${REPORT_DIR} src/test/resources/tests_to_run.csv
                '''
                archiveArtifacts artifacts: 'src/test/resources/tests_to_run.csv', fingerprint: true
            }
        }

        // 4. Run High‑Risk Tests First
        stage('Run High‑Risk Tests') {
            when {
                expression { return fileExists('src/test/resources/tests_to_run.csv') }
            }
            steps {
                sh '''
                    mvn -B test \
                        -Dtest="$(cat src/test/resources/tests_to_run.csv)" \
                        -Dsurefire.failIfNoSpecifiedTests=true
                '''
            }
            post {
                always {
                    junit "${REPORT_DIR}/*.xml"
                    archiveArtifacts artifacts: 'target/test_report.pdf', fingerprint: true
                }
            }
        }

        // 5. Full Test Run (on PRs or every branch)
        stage('Full Test Run') {
            when {
                not { branch 'main' }
            }
            steps {
                sh 'mvn -B test'
            }
            post {
                always {
                    junit "${REPORT_DIR}/*.xml"
                    archiveArtifacts artifacts: 'target/test_report.pdf', fingerprint: true
                }
            }
        }

        // 6. Generate & Publish Report
        stage('Generate Jasper Report') {
            steps {
                sh '''
                    mvn -B package -DskipTests
                    java -cp target/my-app-1.0-SNAPSHOT.jar com.example.GenerateReport \
                        ${REPORT_DIR} ${REPORT_PDF} src/test/resources/jasper/test_report.jrxml
                '''
                // Publish the PDF as a job artifact
                publishHTML([
                    allowMissing: false,
                    keepAll: true,
                    reportDir: 'target',
                    reportFiles: 'test_report.pdf',
                    reportName: 'Test Report',
                    reportTitle: 'Test Execution Report'
                ])
            }
        }
    }

    post {
        success { echo 'Build succeeded!' }
        failure { echo 'Build failed.' }
        always {
            // Clean workspace for next run
            cleanWs()
        }
    }
}
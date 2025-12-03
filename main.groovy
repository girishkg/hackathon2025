import groovy.json.JsonSlurper
import groovy.json.JsonOutput

pipeline {
    agent { label 'N3' } // or any agent with network access

    environment {
        OPENAI_API_KEY = credentials('OPEN-AI-API-KEY-ALL')
        PROJECT_NAME   = "opencms-core"
    }

    stages {
        stage('Checkout') {
            //steps { checkout scm }
            steps {
                git url: 'git@github.com:girishkg/jasperreports.git', branch: 'h2025'
            }

        }

        stage('AI Test Generation') {
            steps {
                script {
                    def prompt = """
                    Generate 5 unit tests for the class ${PROJECT_NAME}.java
                    Use JUnit5, cover edge cases.
                    Output only the test class source code.
                    """
                    def response = httpRequest(
                        httpMode: 'POST',
                        url: 'https://api.openai.com/v1/chat/completions',
                        customHeaders: [[name: 'Authorization', value: "Bearer ${env.OPENAI_API_KEY}"]],
                        contentType: 'APPLICATION_JSON',
                        requestBody: JsonOutput.toJson([
                            model: "gpt-4o-mini",
                            messages: [[role: 'user', content: prompt]]
                        ])
                    )
                    def testSource = new JsonSlurper().parseText(response.content).choices[0].message.content.trim()
                    writeFile file: "src/test/java/${PROJECT_NAME}Tests.java", text: testSource
                }
            }
        }

        stage('Build & Test') {
            steps {
                sh './gradlew bindist install'
            }
        }

        stage('AI Build Performance Prediction') {
            steps {
                script {
                    def metrics = [
                        cpu: env.NODE_TOTAL_CPU,
                        mem: env.NODE_TOTAL_RAM,
                        lastBuildTime: currentBuild.duration
                    ]
                    def predPrompt = "Given CPU ${metrics.cpu} cores, RAM ${metrics.mem}GB, and last build time ${metrics.lastBuildTime}s, predict the next build time in seconds."
                    def predResp = httpRequest(
                        httpMode: 'POST',
                        url: 'https://api.openai.com/v1/chat/completions',
                        customHeaders: [[name: 'Authorization', value: "Bearer ${env.OPENAI_API_KEY}"]],
                        contentType: 'APPLICATION_JSON',
                        requestBody: JsonOutput.toJson([
                            model: "gpt-4o-mini",
                            messages: [[role: 'user', content: predPrompt]]
                        ])
                    )
                    def predTime = new JsonSlurper().parseText(predResp.content).choices[0].message.content.trim()
                    echo "Predicted next build time: ${predTime}s"
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
            junit 'build/test-results/**/*.xml'
            script {
                // Summarise logs via AI
                def summaryPrompt = "Summarise the last 200 lines of build logs in 3 bullets."
                def logs = readFile('build/logs/last-200.log')
                def summaryResp = httpRequest(
                    httpMode: 'POST',
                    url: 'https://api.openai.com/v1/chat/completions',
                    customHeaders: [[name: 'Authorization', value: "Bearer ${env.OPENAI_API_KEY}"]],
                    contentType: 'APPLICATION_JSON',
                    requestBody: JsonOutput.toJson([
                        model: "gpt-4o-mini",
                        messages: [[role: 'user', content: summaryPrompt + "\n\n" + logs]]
                    ])
                )
                def summary = new JsonSlurper().parseText(summaryResp.content).choices[0].message.content.trim()
                echo "Build log summary:\n${summary}"
            }
        }
    }
}
// Jenkins pipeline script for building jasperreports project with AI-generated tests and performance prediction
// mvn clean install source:jar javadoc:jar to build the jasperreports project
// mvn clean test to run tests
// Uses OpenAI API for test generation and build time prediction
// Requires Jenkins plugins: HTTP Request, Pipeline Utility Steps
// Ensure OPEN-AI-API-KEY-ALL credential is set in Jenkins
// Adjust agent label as needed for network access
// Adapted from main.groovy for jasperreports project
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
pipeline {
    agent { label 'N3' } // or any agent with network access

    environment {
        OPENAI_API_KEY = credentials('OPEN-AI-API-KEY-ALL')
        PROJECT_NAME   = "jasperreports"
    }
    stages {
        stage('Checkout') {
            //steps { checkout scm }
            steps {
                git url: 'git@github.com:girishkg/jasperreports.git', branch: 'h2025'
            }
        }
        stage('AI Build Jasperreports') {
            steps {
                sh 'mvn clean install source:jar javadoc:jar -X'
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
                sh 'mvn clean test -X'
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
                    def prediction = new JsonSlurper().parseText(predResp.content).choices[0].message.content.trim()
                    echo "Predicted next build time: ${prediction} seconds"
                }
            }
        }
    }
}
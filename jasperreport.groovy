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
        stage('STAGE-1: Checkout') {
            //steps { checkout scm }
            steps {
                git url: 'git@github.com:girishkg/jasperreports.git', branch: 'h2025'
            }
        }
        stage('STAGE-2: AI Build Jasperreports') {
            steps {
                sh 'mvn clean install source:jar javadoc:jar -X'
            }
        }
        stage('STAGE-3: AI Test Generation') {
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
        stage('STAGE-4: Build & Test') {
            steps {
                sh 'mvn clean test -X'
            }
        }
        stage('STAGE-5: Surefire Report Analysis') {
            steps {
                script {
                    sh 'mvn surefire-report:report'
                }
            }
        }
        stage('STAGE-6: AI Build Performance Prediction') {
            steps {
                script {
                    def metrics = [
                        cpu: sh(script: 'nproc', returnStdout: true).trim(),
                        mem: sh(script: "free -m | awk '/^Mem:/ { print $2 }'", returnStdout: true).trim(),
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
        stage('STAGE-7: Generate Build Logs') {
            steps {
                script {
                    def buildLog = currentBuild.rawBuild.logFile.text
                    writeFile file: "build_logs_${env.BUILD_ID}.txt", text: buildLog
                }
            }
        }
        stage('STAGE-8: Analyze with OpenAI') {
            steps {
                script {
                    def logContent = readFile "build_logs_${env.BUILD_ID}.txt" // Or use the buildLog variable
                    def openaiApiKey = credentials('OPEN-AI-API-KEY-ALL') // Securely retrieve API key

                    def openaiPayload = """
                    {
                        "model": "gpt-4",
                        "messages": [
                            {"role": "system", "content": "You are a helpful assistant that analyzes Jenkins build logs."},
                            {"role": "user", "content": "Analyze the following Jenkins build log for errors, warnings, and potential root causes. Generate a summary report and suggest actionable solutions:\\n\\n${logContent}"}
                        ]
                    }
                    """

                    def openaiResponse = httpRequest(
                        url: 'https://api.openai.com/v1/chat/completions',
                        httpMode: 'POST',
                        contentType: 'APPLICATION_JSON',
                        requestBody: openaiPayload,
                        customHeaders: [[name: 'Authorization', value: "Bearer ${env.OPENAI_API_KEY}"]]
                    ).content

                    def parsedResponse = readJSON text: openaiResponse
                    def analysisReport = parsedResponse.choices[0].message.content
                    writeFile file: 'AI_Analysis_Build_Report.md', text: analysisReport
                }
            }
        }
        stage('STAGE-9: Analyse Surefire Report with AI') {
            steps {
                script {
                    def surefireReport = readFile 'target/site/surefire-report.html'
                    def analysisPrompt = """
                    Analyze the following Surefire report HTML content. Summarize test failures, errors, visualize data and provide insights on improving test coverage and reliability. Output a markdown report.
                    ${surefireReport}
                    """
                    def analysisResp = httpRequest(
                        httpMode: 'POST',
                        url: 'https://api.openai.com/v1/chat/completions',
                        customHeaders: [[name: 'Authorization', value: "Bearer ${env.OPENAI_API_KEY}"]],
                        contentType: 'APPLICATION_JSON',
                        requestBody: JsonOutput.toJson([
                            model: "gpt-4o-mini",
                            messages: [[role: 'user', content: analysisPrompt]]
                        ])
                    )
                    def analysisReport = new JsonSlurper().parseText(analysisResp.content).choices[0].message.content.trim()
                    writeFile file: 'AI_Analysis_Surefire_Report.md', text: analysisReport
                }
            }
        }
        stage('STAGE-10: Publish AI Analysis Reports') {
            steps {
                archiveArtifacts artifacts: 'AI_Analysis_*.md', fingerprint: true
            }
        }
    }
}
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
        // Git info that Jenkins will set after checkout
        GIT_COMMIT   = ''
        GIT_BRANCH   = ''

        // Pull webhook URLs from Jenkins credentials
        //SLACK_WEBHOOK_URL = credentials('slack-webhook-id')   // <-- replace with your ID
        TEAMS_WEBHOOK_URL = credentials('teams-webhook-id')   // <-- replace with your ID
    }
    stages {
        /*
        stage('Clean Workspace') {
            steps {
                deleteDir()
            }
        }
        */
        stage('Checkout') {
            //steps { checkout scm }
            steps {
                git url: 'git@github.com:girishkg/jasperreports.git', branch: 'h2025'
            }
        }
        stage('Prepare Git Diffs') {
            steps {
                script {
                    def commitMessage = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
                    def changedFiles = sh(script: 'git diff --name-only HEAD~1 HEAD', returnStdout: true).trim().split('\n')
                    def filteredFiles = changedFiles.findAll { it.endsWith('.java') || it.endsWith('.xml') || it.endsWith('.properties') }
                    def uniqueFiles = filteredFiles.unique()
                    changedFiles = uniqueFiles
                    def GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    def GIT_BRANCH = sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
                    echo "Commit Message: ${commitMessage}"
                    // ... further processing of changedFiles
                }
            }
        }
        // AI Based Delta builds
        stage('Maven Build Jasperreports') {
            steps {
                sh 'mvn clean install source:jar javadoc:jar'
            }
        }
        /* 
        stage('STAGE-4: AI Test Generation') {
            steps {
                script {
                    def openaiApiKey = credentials('OPENAI_API_KEY') // Retrieve API key from Jenkins credentials
                    def prompt = "Generate Maven test cases for the following changes: ${commitMessage}. Focus on testing the functionality related to these files: ${changedFiles.join(', ')}. Provide the test cases in Java JUnit format."

                    def response = httpRequest(
                        url: 'https://api.openai.com/v1/engines/davinci-codex/completions', // Or a more suitable model
                        httpMode: 'POST',
                        contentType: 'APPLICATION_JSON',
                        customHeaders: [[name: 'Authorization', value: "Bearer ${openaiApiKey}"]],
                        requestBody: JsonOutput.toJson([
                            prompt: prompt,
                            max_tokens: 500, // Adjust as needed
                            temperature: 0.7 // Adjust for creativity vs. determinism
                        ])
                    )
                    def generatedTestCases = readJSON(text: response.content).choices[0].text
                    // ... further processing of generatedTestCases
                }
            }
        }
        // Dynamically generate AI Prompt for test cases based on changed files
        def dynamicPrompt = "Generate 5 unit tests for the following changed files: ${changedFiles.join(', ')}. Use JUnit5, cover edge cases. Output only the test class source code."
        // Send request to OpenAI API to generate test cases
        def aiResponse = httpRequest(
            httpMode: 'POST',
            url: 'https://api.openai.com/v1/chat/completions',
            customHeaders: [[name: 'Authorization', value: "Bearer ${env.OPENAI_API_KEY}"]],
            contentType: 'APPLICATION_JSON',
            requestBody: JsonOutput.toJson([
                model: "gpt-4o-mini",
                messages: [[role: 'user', content: dynamicPrompt]]
            ])
        )
        def testSourceCode = new JsonSlurper().parseText(aiResponse.content).choices[0].message.content.trim()
        // Write the generated test cases to appropriate test files
        */
        // Generate the dynamic test suite commands for AI test generation
        // Here we generate tests for the main project class as an example
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
                sh 'mvn clean test'
            }
        }
        stage('Surefire Report Analysis') {
            steps {
                script {
                    sh 'mvn surefire-report:report'
                }
            }
        }
        stage('AI Build Performance Prediction') {
            steps {
                script {
                    def metrics = [
                        cpu: sh(script: 'nproc', returnStdout: true).trim(),
                        mem: sh(script: "grep MemTotal /proc/meminfo | awk '{print \$2}' | awk '{\$1=\$1/(1024^2); print \$1;}'", returnStdout: true).trim(),
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
        stage('Generate Build Logs') {
            steps {
                script {
                    def buildLog = currentBuild.rawBuild.logFile.text
                    writeFile file: "build_logs_${env.BUILD_ID}.txt", text: buildLog
                }
            }
        }
        // Open-AI has file upload limits, so we read the log file content directly
        // and send it in the request body
        /*
        stage('STAGE-9: Analyze with OpenAI') {
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
        */
        stage('AI Analyse Surefire Report') {
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
        stage('Archive Analysis Reports') {
            steps {
                archiveArtifacts artifacts: 'AI_Analysis_*.md, target/site/surefire-report.html', fingerprint: true
            }
        }
        stage('Commit Analysis Reports to jenkinsbuildreports Repo') {
            steps {
                script {
                    BUILD_ID = env.BUILD_NUMBER
                    sh '''
                        git config --global user.email "girishkg@mememe.in"
                        git config --global user.name "Jenkins CI"
                        rm -rf jenkinsbuildreports
                        git clone git@github.com:girishkg/jenkinsbuildreports.git
                        cd jenkinsbuildreports
                        if [ ! -d ".git" ]; then
                            echo "Git clone failed or directory is not a git repository."
                            exit 1
                        elif git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
                            echo "Successfully inside the git repository."
                            rm -f AI_Analysis_*.md
                        else
                            echo "Not inside a git repository."
                            exit 1
                        fi
                        rm -f AI_Analysis_*.md
                        cp ../AI_Analysis_*.md .
                        git add AI_Analysis_*.md
                        git commit -m "Add AI analysis reports for build of project:  AI-JASPERREPORT-BUILD"
                        git push origin main
                    '''
                }
            }
        }
    }
    // Always Post stage to send teams channel notification
    post {
        always {
            script {
                def buildStatus = currentBuild.currentResult
                def buildUrl = env.BUILD_URL
                def message = "Jenkins Build #${env.BUILD_ID} for project ${PROJECT_NAME} completed with status: ${buildStatus}. View details at: ${buildUrl}, Build Commit: GIT_COMMIT, Branch: GIT_BRANCH, Build Time Prediction stage included, AI-generated tests stage included, Surefire report analyzed by AI, AI analysis reports archived and committed to jenkinsbuildreports repo, Approve PR: PR link here. DENY PR: DENY link here."

                def teamsPayload = JsonOutput.toJson([
                    title: "Jenkins Build Notification",
                    text : message
                ])

                httpRequest(
                    httpMode: 'POST',
                    url: TEAMS_WEBHOOK_URL,
                    contentType: 'APPLICATION_JSON',
                    requestBody: teamsPayload
                )
            }
        }
    }
}
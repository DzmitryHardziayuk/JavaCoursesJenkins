def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def dockerRegistry = "127.0.0.1:80"
    def username = scm.getUserRemoteConfigs()[0].getUrl().tokenize('/')[3].split("\\.")[0].toLowerCase() ?: "undefined_user"

    final String repositoryUrl = "$dockerRegistry/java_courses_2018/${username}".toLowerCase()

    node() {
        stage("Checkout") {
            checkout(scm)
            env.JAVA_HOME = "${tool 'java8'}"
            env.M2_HOME = "${tool 'M3'}"
            env.PATH = "${env.PATH}:${env.JAVA_HOME}/bin:${env.M2_HOME}/bin"
            env.COMMIT_MESSAGE = sh(script: "git log -1 --pretty=%B", returnStdout: true).trim()
            sh "printenv"
        }

        def scanResult
        stage("Install") {
            try {
                sh "mvn clean install -DskipTests"

                scanResult = scanProject()
            } finally {
                try {
                    junit '**/target/surefire-reports/*.xml'
                } catch (junitError) {
                }
            }
        }

        stage("Docker") {

            def startScript = generateStartScript(scanResult)
            def dockerfile = generateDockerfile(scanResult)

            copyFiles(scanResult)

            writeFile file: "Dockerfile", text: dockerfile
            writeFile file: "jettystart.sh", text: startScript.toString()
            sh "chmod +x jettystart.sh"
            sh "chmod 755 deploy_modules/*"


            println 'Start script'
            println startScript
            println 'Start script'

            println 'Dockerfile'
            println dockerfile
            println 'Dockerfile'


            withDockerServer([uri: "unix:///var/run/docker.sock"]) {
                withDockerRegistry([url: "http://127.0.0.1:80/"]) {

                    def buildarg = ""

                    scanResult.each { def module ->
                        buildarg += "--build-arg ports=$module.port "
                    }

                    def image = docker.build(repositoryUrl, "$buildarg.")
                    image.push()
                }
            }
        }

        stage("Start container") {
            final String containerName = username.toLowerCase()
            sh "docker stop $containerName || exit 0"
            sh "docker rm $containerName || exit 0"
            sh "docker run -dP --name $containerName $repositoryUrl"
            sh "docker ps | grep java\\_courses\\_2018 > /var/lib/jenkins/userContent/dockerPs.txt"
            final String currentHost = sh(
                    script: "cat /etc/hosts | head -2 | tail -1 | awk '{print \$2}'",
                    returnStdout: true
            ).trim()
            println "http://$currentHost:8080/userContent/dockerPs.txt"
        }
    }
}


def scanProject() {
    def executables = []
    def pom = readMavenPom file: 'pom.xml'
    pom.getModules().each { String module ->

        def module_pom = readMavenPom file: "$module/pom.xml"

        module_pom?.build?.plugins?.each { def plugin ->

            if ("jetty-maven-plugin" == plugin.artifactId) {
                def artifactId = module_pom.artifactId
                def version = module_pom.version ? module_pom.version : module_pom?.parent?.version
                def packaging = module_pom.packaging
                def file = "$artifactId-$version.$packaging"
                def filePath = "$artifactId/target/"
                def config = plugin?.configuration

                def path = config?.getChild('webApp')?.getChild('contextPath')?.value
                def port = config?.getChild('httpConnector')?.getChild('port')?.value

                path = path ?: '/'
                port = port ?: 8080

                executables.add([
                        port    : port,
                        path    : path,
                        file    : file,
                        filePath: filePath])
            }
        }
    }
    executables
}

def generateStartScript(def modules) {

    StringBuilder startScript = new StringBuilder("#!/bin/sh\n")

    modules.each { def module ->
        startScript.append("nohup java -jar /usr/local/jettyapps/jetty-runner-9.4.8.v20171121.jar --port ${module.port} /usr/local/jettyapps/${module.file} > springlog.log 2>&1 &\n")
    }

    startScript.append("echo \"Servers started\"\n")
    startScript.append("tail -f springlog.log")

    startScript
}

def generateDockerfile(def modules) {

    def dockerfile = """
FROM jetty:9.4-alpine
ADD http://central.maven.org/maven2/org/eclipse/jetty/jetty-runner/9.4.8.v20171121/jetty-runner-9.4.8.v20171121.jar /usr/local/jettyapps/
COPY ./deploy_modules/* /usr/local/jettyapps/"""

    modules.each { def module ->
        dockerfile += "\nEXPOSE ${module.port}"
    }

    dockerfile += """
COPY jettystart.sh /usr/local/bin/
USER root
RUN chmod 755 /usr/local/jettyapps/jetty-runner-9.4.8.v20171121.jar
USER jetty
ENTRYPOINT ["jettystart.sh"]"""


    dockerfile
}

def copyFiles(def modules) {
    sh "mkdir -p deploy_modules"
    sh "rm -f deploy_modules/*"

    modules.each { def module ->
        def file = module.filePath + module.file
        sh "cp $file deploy_modules/"
    }
}


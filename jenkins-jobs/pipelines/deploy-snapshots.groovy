import groovy.json.*
import java.util.stream.*

if (
    !DEBEZIUM_REPOSITORY ||
    !DEBEZIUM_BRANCH ||
    !DEBEZIUM_ADDITIONAL_REPOSITORIES
) {
    error 'Input parameters not provided'
}

GIT_CREDENTIALS_ID = 'debezium-github'

DEBEZIUM_DIR = 'debezium'
HOME_DIR = '/home/centos'

def additionalDirs = []
node('Slave') {
    try {
        stage('Initialize') {
            dir('.') {
                deleteDir()
            }
            checkout([$class                           : 'GitSCM',
                      branches                         : [[name: "*/$DEBEZIUM_BRANCH"]],
                      doGenerateSubmoduleConfigurations: false,
                      extensions                       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: DEBEZIUM_DIR]],
                      submoduleCfg                     : [],
                      userRemoteConfigs                : [[url: "https://$DEBEZIUM_REPOSITORY", credentialsId: GIT_CREDENTIALS_ID]]
            ]
            )
            DEBEZIUM_ADDITIONAL_REPOSITORIES.split().each {
                def (id, repository, branch) = it.split('#')
                checkout([$class                           : 'GitSCM',
                          branches                         : [[name: "*/$branch"]],
                          doGenerateSubmoduleConfigurations: false,
                          extensions                       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: id]],
                          submoduleCfg                     : [],
                          userRemoteConfigs                : [[url: "https://$repository", credentialsId: GIT_CREDENTIALS_ID]]
                ]
                )
                additionalDirs << id
            }

            dir(DEBEZIUM_DIR) {
                ORACLE_ARTIFACT_VERSION = (readFile('pom.xml') =~ /(?ms)<version.oracle.driver>(.+)<\/version.oracle.driver>/)[0][1]
                ORACLE_ARTIFACT_DIR = "$HOME_DIR/oracle-libs/${ORACLE_ARTIFACT_VERSION}.0"
            }

            dir(ORACLE_ARTIFACT_DIR) {
                sh "mvn install:install-file -DgroupId=com.oracle.instantclient -DartifactId=ojdbc8 -Dversion=$ORACLE_ARTIFACT_VERSION -Dpackaging=jar -Dfile=ojdbc8.jar"
                sh "mvn install:install-file -DgroupId=com.oracle.instantclient -DartifactId=xstreams -Dversion=$ORACLE_ARTIFACT_VERSION -Dpackaging=jar -Dfile=xstreams.jar"
            }
        }

        stage('Build and deploy Debezium') {
            dir(DEBEZIUM_DIR) {
                sh "mvn clean deploy -U -s $HOME/.m2/settings-snapshots.xml -DdeployAtEnd=true -DskipITs -DskipTests -Passembly,oracle  -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn -Dmaven.wagon.http.pool=false -Dmaven.wagon.httpconnectionManager.ttlSeconds=120 -Dmaven.wagon.rto=20000 -Dmaven.wagon.http.retryHandler.count=1 -Dmaven.wagon.http.serviceUnavailableRetryStrategy.retryInterval=5000"
            }
        }

        additionalDirs.each { id ->
            stage("Build and deploy Debezium ${id.capitalize()}") {
                dir(id) {
                    sh "mvn clean deploy -s $HOME/.m2/settings-snapshots.xml -DdeployAtEnd=true -DskipITs -DskipTests -Passembly -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn -Dmaven.wagon.http.pool=false -Dmaven.wagon.httpconnectionManager.ttlSeconds=120 -Dmaven.wagon.rto=20000 -Dmaven.wagon.http.retryHandler.count=1 -Dmaven.wagon.http.serviceUnavailableRetryStrategy.retryInterval=5000"
                }
            }
        }
    } finally {
        mail to: MAIL_TO, subject: "${JOB_NAME} run #${BUILD_NUMBER} finished", body: "Run ${BUILD_URL} finished with result: ${currentBuild.currentResult}"
    }
}

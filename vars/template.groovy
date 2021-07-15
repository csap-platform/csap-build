//
// Template parameters
//
// POMS - used in builds
// ---------------------
// parentPom - The parent pom or the "poms".   The parent will be built in the build.
// poms - The poms being built in this build.  (Note: a parent type of pom can be a "poms" if it is release
//
// dockerPoms - A docker build of a pom. Multiple poms can be comma separated.
//
// BUILD DATA - Extra data for the build
// -------------------------------------
// props - A Map of key/value properties that are added to the mvn call (as -D)
// propsFile - A file of a Map key/value properties that are added to the props of the build
// skipTest - Skip tests for this build
// skipDeploy - Skip deploy for this build
//
// TAG DATA
// --------
// externalTagsUrls - a list of git urls that this build will tag not in this repo (assuming we have a tag_name define)
// externalTagsFile - a file in json format of a list of git urls this build will tag not in this repo
//
// PARAMETERS - displayed in Jenkins's "Build Now" (everything is turned off/false by default)
// -------------------------------------------------------------------------------------------
// enableGranparentVersion - Provide a build parameter that can version the parentPom's parent version.
// enableParentVersion - Provide a build parameter that can version the parentPom.
//        (parent of poms/dockerPoms, version of parentPom)
// enableVersion - Provide a build parameter that can version the poms/dockerPoms
// enableTag - Provide the tag parameter.
// extraParams - A list of map used in creating string parameters (name, defaultValue, description)
//
//

def call(Map pipelineParams) {

	utils.print_header ( "kicking off csap pipleline using css-build/vars/template.groovy")

    def buildParams = []
    
    if (pipelineParams.enableGrandparentVersion) {
        buildParams += [
            string(defaultValue: params['GRANDPARENT_VERSION']?:'', description: 'Grandparent pom version (empty uses version in parent pom)', name: 'GRANDPARENT_VERSION')
        ]
	}

    if (pipelineParams.enableParentVersion) {
        buildParams += [
            string(defaultValue: params['PARENT_VERSION']?:'', description: 'Parent pom version (empty uses version in parent pom/pom/)', name: 'PARENT_VERSION'),
        ]
    }

    if (pipelineParams.enableVersion) {
        buildParams += [
            string(defaultValue: params['VERSION']?:'', description: 'Deploy version (empty uses version in pom)', name: 'VERSION'),
        ]
    }

    if (pipelineParams.extraParams) {
        buildParams += pipelineParams.extraParams
    }

    if (pipelineParams.enableTag) {
        buildParams += [
           string(defaultValue: 'DEFAULT', description: "Tag name (empty will not tag. \'DEFAULT\' will tag \'${pipelineParams.defaultTag}-VERSION\')", name: 'TAG_NAME'),
           booleanParam(defaultValue: false, description: 'rebuild if tag already exists', name: 'FORCE_TAG'),
        ]
    }

    if (buildParams) {
        utils.print_header "Using buildParams of: ${buildParams}"
        properties([parameters(buildParams)])
    }

    pipeline {
    
    
    	// https://www.jenkins.io/doc/book/pipeline/syntax/#options
    	//options { 
    	//	disableConcurrentBuilds() 
    	//	timeout(time: 1, unit: 'MINUTES')
    	//	retry(3)
		//}
    
        agent {
            label 'csap-build-cluster'
        }

        tools {
            maven 'Maven_3.3.9'
            jdk 'OpenJDK11'
        }

        stages {
            stage('Setup') {
                steps {
                    script {
                        // add externalTagsFile to the extenalTags
                        if (pipelineParams.externalTagsFile) {
                            def urls = readJSON file: pipelineParams.externalTagsFile
                            pipelineParams.externalTagsUrls =  pipelineParams.externalTagsUrl?:[] + urls
                        }

                        // add propsFile to props
                        pipelineParams.props = pipelineParams.props?:[:]
                        if (pipelineParams.propsFile) {
                            def readProps = readJSON file: pipelineParams.propsFile
                            pipelineParams.props +=  readProps?:[:] 
                        }

                        // add the parameters to the properties
                        pipelineParams.props += params?:[:]

                        utils.setup(pipelineParams)
                        if (!utils.checkTag(pipelineParams)) {
                            return
                        }
                        
                        // ssh suffers from concurrency issues - switch to http
//                        def sessionUser = "bb-cloud-builder"
//                        utils.print_header "starting ssagent session as user $sessionUser"
//                        sshagent(credentials : [ "$sessionUser" ]) {
                        

//                		def now = new Date()	
//                		def credFileName = "csap-cred-file-" + now.format("HH-mm-ss-SSS", TimeZone.getTimeZone('UTC'))
                        //
                        //  release jobs will tag git repos; requires credentials be available
                        // 'pnightingale-http-cloud-bb'
                        //
//                        def sessionUser = "shr-sensus-rni-user2"
                        def sessionUser = "bb-cloud-builder-http"
                		def credFileName = "csap-build-credentials"
                        utils.print_header "withCredentials session - user: '$sessionUser' stored in '$credFileName'"
                        withCredentials([usernamePassword(credentialsId: sessionUser, passwordVariable: 'adminPass', usernameVariable: 'adminUser')]) {
                            sh "echo https://$adminUser:$adminPass@bitbucket.org > ~/$credFileName"
                            sh "git config --local credential.helper store --file=~/$credFileName"
                            sh "cat ~/$credFileName"

							utils.reversion(pipelineParams)

							utils.build(pipelineParams)

							utils.test(pipelineParams)

							utils.deploy(pipelineParams)

							utils.tag(pipelineParams)

							utils.tagOtherRepos(pipelineParams)
							
							//sh "rm ~/$credFileName"

						}

                    }
                }
            }
        }

        post {
            always {
                script {
                    utils.postProcessing('peter.nightingale@xylem.com', true)
                }
            }
        }
    }

}

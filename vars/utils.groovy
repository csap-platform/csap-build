
def settingsFile


def print_header( p ) {
	LINE = "\n_______________________________________________________________________________________________\n" ;
	println "\n\n ${LINE} \n ${p} ${LINE}"
}



// checkTag has 3 states
// true - everything is ok and continue build
// false - tag already exists and we want to exit successfully
// error - tag already exists and we exit with error
def checkTag(Map p) {
	// checking tag
	def tagName = getTagName(p)
	if (tagName) {
		print_header "This build will be tagged: ${tagName}"
		def tagExists = sh returnStdout: true, script: "git tag -l ${tagName}"

		// if tag exists and we do have SKIP_BUILD_IF_TAGGED set
		if (tagExists && p.props.SKIP_BUILD_IF_TAGGED) {
			echo("Tag already exists, skipping build - ${tagName}")
			return false
		}

		// if tag exists and we do not have FORCE_TAG set
		if (tagExists && !p.props.FORCE_TAG) {
			error("Tag already exists - ${tagName}")
		}
	} else {
		echo "This build will NOT be tagged"
	}
	return true
}

def setup(Map p) {


	print_header "setup -  pipeline ${p}"

	print_header "setup - java"
	sh 'java -version'
	
	
	settingsFile = getAsFile('csap-settings.xml')
	
	def isDebug = false
	if ( isDebug ) {
	              
		print_header "setup - maven"
		sh 'mvn help:system'
		
		print_header "setup - pipeline"
		echo "Build env = ${env}"
		echo "Build pipeline = ${p}"
	
		sh "cat ${settingsFile}"
  	}

}

def getAsFile(String resource) {
	
	// file is going into the workspace, we may want it somewhere else
	def filepath = resource

	// note: we get the file on every call otherwise we might be using resource from
	// previous run.
	print_header "get file ${resource}"
	writeFile file:"${filepath}", text:libraryResource("${resource}")
	return filepath
}

def mvnCmd(String stageName, String mvnFlags, String poms, Map props) {

	def mvnProps = ""
	props.each{ k, v -> mvnProps += v?"-D${k}=${v} ":''}
	
	def pomArray = poms.split(',')
	
	pomArray.each { pom ->
	
		// title is pomfile minus "/pom.xml"
		def title = pom.substring(0, pom.length()-8)
		print_header "Running stage '${stageName}' for '${title}'"
		
		stage("${stageName} ${title}") {
			sh "mvn -f ${pom} -s ${settingsFile} ${mvnProps} ${mvnFlags}"
		}
	}
}

def reversionFlags(String parentVersion, String version) {
	String flags = ""
	if (parentVersion) {
		flags += "versions:update-parent \"-DparentVersion=[${parentVersion}]\" -DallowSnapshots=true "
	}
	if (version) {
		flags += "versions:set -DnewVersion=${version} "
	}
	return flags
}

def tagCmds(String tagName) {
	// delete currently tag if exists
	sh "git tag -d ${tagName} || true"
	sh "git tag ${tagName}"
	// delete remote tag if exists
	sh "git push origin :refs/tags/${tagName}"
	sh "git push --tags"
}

def reversion(Map p) {
	// REVERSION
	if (p.parentPom) {
		String flags = reversionFlags( p.props.GRANDPARENT_VERSION, p.props.PARENT_VERSION)
		if (flags) {
			mvnCmd('Reversion', flags, p.parentPom, p.props)
		}
		// need to build parent pom because it is needed in versioning children.
		mvnCmd('Build', '-DskipTests -B -U -fae install', p.parentPom, p.props)
	}
	if (p.poms) {
		String flags = reversionFlags(p.props.PARENT_VERSION, p.props.VERSION)
		if (flags) {
			mvnCmd('Reversion', flags, p.poms, p.props)
		}
	}
	if (p.dockerPoms) {
		String flags = reversionFlags(p.props.PARENT_VERSION, p.props.VERSION)
		if (flags) {
			mvnCmd('Reversion', flags, p.dockerPoms, p.props)
		}
	}
}

def clean(Map p) {
	def cmd = 'clean'
	if (p.parentPom) {
		mvnCmd('Clean', cmd, p.parentPom, p.props)
	}
	if (p.poms) {
		mvnCmd('Clean', cmd, p.poms, p.props)
	}
	if (p.dockerPoms) {
		mvnCmd('Clean', cmd, p.dockerPoms, p.props)
	}
}


def build(Map p) {
	// BUILD (skipping dockerPoms because all mvn calls run the complete build)
	if (p.parentPom) {
		mvnCmd('Build', '-DskipTests -B -U -fae install', p.parentPom, p.props)
	}
	if (p.poms) {
		mvnCmd('Build', '-DskipTests -B -U -fae install', p.poms, p.props)
	}
}

def test(Map p) {
	// TEST (only need to run poms since parentPoms and dockerPoms don't have normal tests)
	if (p.poms && !p.skipTests)
	{
		mvnCmd('Test', '-B -U --fae verify', p.poms, p.props)
	}
}

def deploy(Map p) {
	// DEPLOY
	if (!p.skipDeploy) {
		if (p.parentPom) {
			mvnCmd('Deploy', '-DskipTests -B -U --fae deploy', p.parentPom, p.props)
		}
		if (p.poms) {
			mvnCmd('Deploy', '-DskipTests -B -U --fae deploy', p.poms, p.props)
		}
		if (p.dockerPoms) {
			// lock, multiple builds can run at the same time. A parallel build can prune
			// docker while we are running this build.
			lock('csap-docker') {
				echo "remove old docker stuff"
				sh "docker system prune -af"
				sh "docker volume prune -f"
				mvnCmd('Build_Deploy', "-B -U -fae deploy", p.dockerPoms, p.props)
			}
		}
	}
}

def getTagName(Map p) {
	// TAG build
	def tagName
	if (p.props.TAG_NAME) {
		if (p.props.TAG_NAME == "DEFAULT") {
			// defaultTag + version
			tagName = p.defaultTag + "-"
			if (p.props.VERSION) {
				tagName += p.props.VERSION
			} else if (p.props.PARENT_VERSION) {
				tagName += p.props.PARENT_VERSION
			} else {
				tagName += 'UNDEFINED'
			}
		} else {
			// custom name
			tagName = p.props.TAG_NAME
		}
	}
	return tagName
}


def tag(Map p) {
	// TAG build
	def tagName = getTagName(p)
	if (tagName) {
		print_header "Tagging build as ${tagName}"
		tagCmds(tagName)
	}
}


def tagOtherRepos(Map p) {

	if (p.externalTagsUrls && p.props.TAG_NAME) {
		print_header "Tagging other repos: + ${p.externalTagsUrls}"
		def workDir = 'TEMP_TAGGING'

		p.externalTagsUrls.each { repo ->
			print_header "Cloning and Tagging ${repo} with tag ${p.props.TAG_NAME}"
			sh "rm -r -f ${workDir}"
			dir (workDir) {
				git url: repo
				tagCmds(p.props.TAG_NAME)
			}
		}
	}
}

def postProcessing(String emailTo, boolean allowEmptyResults = true) {

	junit testResults:'**/target/surefire-reports/*.xml', allowEmptyResults:allowEmptyResults

	def emailBody = 'Build Failed'
	def emailSubject = "${env.JOB_NAME} - Build# ${env.BUILD_NUMBER} - ${env.BUILD_STATUS}"

	//    bitbucketStatusNotify (credentialsId: 'bb-cloud', buildState: (currentResult == 'FAILURE' ? 'FAILED' : 'SUCCESSFUL'))
	print_header "currentBuild.currentResult = ${currentBuild.currentResult}"
	if (currentBuild.currentResult == 'FAILURE') { // Other values: SUCCESS, UNSTABLE
		// Send an email only if the build status has changed from green/unstable to red
		emailext subject: emailSubject, body: emailBody, recipientProviders: [
			[$class: 'CulpritsRecipientProvider']
		], replyTo: "peter.nightingale@xyleminc.com", to: emailTo
	}
}

return this;

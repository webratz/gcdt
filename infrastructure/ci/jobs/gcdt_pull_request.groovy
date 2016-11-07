import utilities.InfraUtilities

// this is the pull request builder job
// TODO: activate the github hook to speed up the build


environ = InfraUtilities.getEnv() //TODO: this does not work with central Jenkins:
def branchToCheckout = InfraUtilities.getBranch()
//def slackChannel = InfraUtilities.getSlackChannel()

out.println(branchToCheckout)

def credentialsToCheckout = "psd-frontend-jenkins_username-password"
def baseFolder = "infrastructure/ci"
def artifactBucket = "glomex-infra-reposerver-prod"
def venvScript = baseFolder + "/scripts/prepare_virtualenv.sh"
def buildScript = baseFolder + "/scripts/build_package.sh"
def lifecycleScript = baseFolder + "/scripts/gcdt_lifecycle.sh"

def packageName = 'gcdt'
def jobName = "glomex-cloud-deployment-tools/" + packageName + "_pull_request"
def repository = "glomex/glomex-cloud-deployment-tools"

folder("glomex-cloud-deployment-tools") {
}

job(jobName) {
    environmentVariables {
        keepSystemVariables(true)
        keepBuildVariables(true)
        env('ENV', environ)
        env('PACKAGE_NAME', packageName)
        env('ARTIFACT_BUCKET', artifactBucket)
        env('PYTHONUNBUFFERED', '1')
        env('AWS_DEFAULT_REGION', 'eu-west-1')
        env('BRANCH', 'develop')
        // http://chase-seibert.github.io/blog/2014/01/12/python-unicode-console-output.html
        env('PYTHONIOENCODING', 'UTF-8')
        // vars specific to gcdt
        env('ACCOUNT', 'infra')
        env('BUCKET', artifactBucket + '/pypi/packages/' + packageName + '/')
    }

    parameters {
        labelParam('NODE') {
            defaultValue('infra-dev')
        }
    }

    scm {
        git {
            remote {
                github(repository, 'https')
                credentials(credentialsToCheckout)
                branch('${sha1}')
                refspec('+refs/pull/*:refs/remotes/origin/pr/*')
            }
            configure { gitScm ->
                gitScm / 'extensions' << 'hudson.plugins.git.extensions.impl.UserExclusion' {
                    excludedUsers('Jenkins Continuous Integration Server')
                }
            }
        }
    }

    throttleConcurrentBuilds {
        maxTotal(1)
    }

    triggers {
        githubPullRequest {
            orgWhitelist(['glomex'])  // ma_github_org
            cron('H/5 * * * *')
            //onlyTriggerPhrase(false)
            onlyTriggerPhrase()
            triggerPhrase('@alexa please test')
            useGitHubHooks(false)
            //useGitHubHooks()
            permitAll()

            autoCloseFailedPullRequests(false)
            allowMembersOfWhitelistedOrgsAsAdmin(false)
            // whitelist target branch is missing in the config??
            extensions {
                commitStatus {
                    //context('deploy to staging site')
                    //triggeredStatus('starting deployment to staging site...')
                    //startedStatus('deploying to staging site...')
                    //statusUrl('http://mystatussite.com/prs')
                    completedStatus('SUCCESS', 'All is well')
                    completedStatus('FAILURE', 'Something went wrong.')
                    completedStatus('PENDING', 'processing...')
                    completedStatus('ERROR', 'Something went wrong!')
                }
            }
        }
    }

    wrappers {
        preBuildCleanup()
        colorizeOutput()

        credentialsBinding {
            usernamePassword('GIT_CREDENTIALS',
                    'psd-frontend-jenkins_username-password')
        }
    }

    steps {
        // check env
        shell('''
            echo $ghprbPullId
            if [ -v $ghprbPullId ]
            then
                echo "not inside a pull request - bailing out!"
                exit 1
            fi
              '''.stripIndent())

        // prepare virtualenv
        shell(readFileFromWorkspace(venvScript))

        // run the unit tests
        shell('./venv/bin/python -m pytest --cov gcdt tests/test_*')

        // run the style checks
        shell('./venv/bin/pylint gcdt || true')

        // build the package and publish to repo server
        shell(readFileFromWorkspace(buildScript))

        // run the gcdt lifecycle 
        shell(readFileFromWorkspace(lifecycleScript))

        // are we done?
        shell("echo 'done done done'")
    }
}
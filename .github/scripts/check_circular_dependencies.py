import glob
import os
import re
from github import Github

serviceDirectory = 'asaas/domain/grails-app/'
dependenciesGraph = {}

def extractServiceName(filePath):
    baseName = os.path.basename(filePath)
    return os.path.splitext(baseName)[0]

def findCycleWithDepthFirstSearch(serviceName, visited=None, cycle=None):
    if visited is None:
        visited = []
    if cycle is None:
        cycle = []

    if serviceName in visited:
        cycle.append(serviceName)
        return True

    visited.append(serviceName)

    if serviceName in dependenciesGraph:
        for dependency in dependenciesGraph[serviceName]:
            if findCycleWithDepthFirstSearch(dependency, visited, cycle):
                cycle.append(serviceName)
                return True

    visited.remove(serviceName)
    return False


def findDependencies(filePath):
    with open(filePath, 'r') as file:
        content = file.read()

        service_names = []
        def_matches = re.compile(r'def\s+(\w+Service)\b').findall(content)
        explicit_matches = re.compile(r'\b\w+Service\s+(\w+Service)\b').findall(content)

        if def_matches:
            service_names += def_matches

        if explicit_matches:
            service_names += explicit_matches

        if service_names:
            dependenciesGraph[extractServiceName(filePath)] = list(map(lambda x: x.title(), service_names))

serviceFiles = glob.glob(os.path.join(serviceDirectory, '**/*Service.groovy'), recursive=True)

for filePath in serviceFiles:
    findDependencies(filePath)

circularReferences = []
for serviceName in dependenciesGraph:
    cycle = []
    if findCycleWithDepthFirstSearch(serviceName, cycle=cycle):
        cycle.reverse()
        circularReferences.append(cycle)

if circularReferences:
    github_token = os.getenv('GITHUB_TOKEN')
    g = Github(github_token)
    repo = g.get_repo(os.getenv('GITHUB_REPOSITORY'))
    pr_number = os.getenv('GITHUB_REF').split('/')[-2]
    pr = repo.get_pull(int(pr_number))

    comment_body = "### Circular Dependency Detected\n\n"
    comment_body += "The following circular dependencies were found in your changes:\n\n"
    for cycle in circularReferences:
        comment_body += " -> ".join(cycle) + "\n"

    pr.create_issue_comment(comment_body)
    print("Circular dependencies found and commented on the PR." + comment_body)
else:
    print("No circular dependencies found.")

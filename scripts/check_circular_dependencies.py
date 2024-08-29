import os
import re
import networkx as nx
from github import Github

# Caminho do diretório onde os services estão localizados
SERVICES_PATH = 'asaas/domain/grails-app/services/'

# Inicializa o grafo para representar as dependências entre os services
dependency_graph = nx.DiGraph()

# Função para extrair as dependências de um service
def extract_dependencies(service_content):
    pattern = re.compile(r'(?<!\w)([A-Z][a-zA-Z]+Service)\b')
    return pattern.findall(service_content)

# Percorre os arquivos de service para construir o grafo de dependências
for root, dirs, files in os.walk(SERVICES_PATH):
    for file in files:
        if file.endswith("Service.groovy"):
            service_name = file.replace(".groovy", "")
            with open(os.path.join(root, file), 'r') as f:
                content = f.read()
                dependencies = extract_dependencies(content)
                for dep in dependencies:
                    dependency_graph.add_edge(service_name, dep)

# Verifica se há ciclos no grafo de dependências
cycles = list(nx.simple_cycles(dependency_graph))

if cycles:
    # Conecta-se ao GitHub para comentar no PR
    github_token = os.getenv('GITHUB_TOKEN')
    g = Github(github_token)
    repo = g.get_repo(os.getenv('GITHUB_REPOSITORY'))
    pr_number = os.getenv('GITHUB_REF').split('/')[-2]
    pr = repo.get_pull(int(pr_number))
    
    # Constrói a mensagem de comentário
    comment_body = "### Circular Dependency Detected\n\n"
    comment_body += "The following circular dependencies were found in your changes:\n\n"
    for cycle in cycles:
        comment_body += " -> ".join(cycle) + "\n"

    #pr.create_issue_comment(comment_body)
    print("Circular dependencies found and commented on the PR." + comment_body)
else:
    print("No circular dependencies found.")

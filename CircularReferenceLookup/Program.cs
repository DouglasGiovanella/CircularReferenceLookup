using System.Text.RegularExpressions;
using Newtonsoft.Json;

namespace CircularReferenceLookup {
    internal static partial class Program {

        private static readonly List<string?> IgnoredServices = new() {
            "springSecurityService",
            "groovyPageRenderer"
        };

        private static async Task Main(string[] args) {
            string[] files = Directory.GetFiles(
                @"/Users/douglasgiovanella/Documents/workspace/asaas",
                "*Service.groovy",
                SearchOption.AllDirectories);

            Dictionary<string, List<string>> serviceReferences = await BuildServices(files);

            List<List<string>> findCycles = serviceReferences.FindCycles();

            Console.WriteLine($"Referencias: {findCycles.Count}");
            Console.WriteLine(JsonConvert.SerializeObject(findCycles, Formatting.Indented));
            Console.ReadKey();
        }

        private static async Task<Dictionary<string, List<string>>> BuildServices(string[] servicesPaths) {
            Dictionary<string, List<string>> references = new();
            Dictionary<string, string> classFileReference = new();

            foreach (string path in servicesPaths) {
                string className = Path.GetFileNameWithoutExtension(path);

                string injectionName = char.ToLower(className[0]) + className[1..];
                classFileReference[injectionName] = path;
            }

            var tasks = new List<Task>();

            foreach ((string key, string value) in classFileReference)
            {
                tasks.Add(Task.Run(async () =>
                {
                    var result = await FindServicesInjections(value);
                    references[key] = result;
                }));
            }

            // Aguarda todas as tarefas serem concluídas
            await Task.WhenAll(tasks);

            return references;
        }

        private static async Task<List<string>> FindServicesInjections(string servicePath) {
            List<string> services = new();

            string serviceContent = await File.ReadAllTextAsync(servicePath);

            MatchCollection defMatches = MyRegex().Matches(serviceContent);
            foreach (Match match in defMatches) {
                services.Add(match.Groups[1].Value);
            }

            MatchCollection explicitMatches = MyRegex1().Matches(serviceContent);
            foreach (Match match in explicitMatches) {
                services.Add(match.Groups[1].Value);
            }

            return services;
        }

        [GeneratedRegex("def\\s+(\\w+)")]
        private static partial Regex MyRegex();

        [GeneratedRegex("\\b\\w+Service\\s+(\\w+Service)\\b")]
        private static partial Regex MyRegex1();
    }
}
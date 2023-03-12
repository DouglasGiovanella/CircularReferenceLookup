using Newtonsoft.Json;

namespace CircularReferenceLookup {
    internal static class Program {

        private static readonly List<string?> IgnoredServices = new() {
            "springSecurityService",
            "groovyPageRenderer"
        };

        private static async Task Main(string[] args) {
            string[] files = Directory.GetFiles(@"C:\Users\Douglas Giovanella\Downloads\asaas-release", "*Service.groovy", SearchOption.AllDirectories);

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

            foreach ((string key, string value) in classFileReference) {
                references[key] = await FindServicesInjections(value);
            }

            return references;
        }

        private static async Task<List<string?>> FindServicesInjections(string servicePath) {
            List<string?> services = new();

            string[] lines = await File.ReadAllLinesAsync(servicePath);
            foreach (string line in lines) {
                if (string.IsNullOrWhiteSpace(line)) continue;

                string sanitizedLine = line.Trim();
                string? serviceName = TryExtractServiceName(sanitizedLine);

                if (!string.IsNullOrEmpty(serviceName) && !IgnoredServices.Contains(serviceName)) services.Add(serviceName);
            }

            return services;
        }

        private static string? TryExtractServiceName(string sanitizedLine) {
            string? serviceName = null;

            if (sanitizedLine.StartsWith("def ") && sanitizedLine.EndsWith("Service")) {
                // Bean injection
                serviceName = sanitizedLine.Replace("def ", string.Empty);
            } else if (sanitizedLine.Contains("applicationContext.")) {
                // Context access
                serviceName = sanitizedLine.Split("applicationContext.").Last().Split(".").First();
            }

            return serviceName?.Trim();
        }
    }
}
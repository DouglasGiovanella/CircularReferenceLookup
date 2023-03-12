namespace CircularReferenceLookup;

public static class DependencyExtensions {

    public static List<List<T>> FindCycles<T, TValueList>(this IDictionary<T, TValueList> listDictionary) where TValueList : class, IEnumerable<T> where T : notnull {
        return listDictionary.Keys.FindCycles(key => listDictionary!.ValueOrDefault(key, null) ?? Enumerable.Empty<T>());
    }

    private static TValue? ValueOrDefault<TKey, TValue>(this IDictionary<TKey, TValue?> dictionary, TKey key, TValue? defaultValue) {
        if (dictionary.TryGetValue(key, out TValue? value)) return value;

        return defaultValue;
    }

    private static void DepthFirstSearch<T>(T node, Func<T, IEnumerable<T>> lookup, List<T> parents, Dictionary<T, VisitState> visited, List<List<T>> cycles) where T : notnull {
        VisitState state = visited.ValueOrDefault(node, VisitState.NotVisited);
        if (state == VisitState.Visited) return;

        if (state == VisitState.Visiting) {
            cycles.Add(parents.Concat(new[] { node }).SkipWhile(parent => !EqualityComparer<T>.Default.Equals(parent, node)).ToList());
        } else {
            visited[node] = VisitState.Visiting;
            parents.Add(node);
            foreach (T child in lookup(node)) {
                DepthFirstSearch(child, lookup, parents, visited, cycles);
            }

            parents.RemoveAt(parents.Count - 1);
            visited[node] = VisitState.Visited;
        }
    }

    private static List<List<T>> FindCycles<T>(this IEnumerable<T> nodes, Func<T, IEnumerable<T>> edges) where T : notnull {
        List<List<T>> cycles = new();
        Dictionary<T, VisitState> visited = new();
        foreach (T node in nodes) {
            DepthFirstSearch(node, edges, new List<T>(), visited, cycles);
        }

        return cycles;
    }
}

internal enum VisitState {
    NotVisited,
    Visiting,
    Visited
};
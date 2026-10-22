"""AST-based Java metrics. Optional dependencies are isolated in .tools/metrics."""
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/metrics'))
from tree_sitter import Language, Parser
import tree_sitter_java

parser = Parser(Language(tree_sitter_java.language()))

def measure(files):
    methods = []
    single_letters = []
    long_lines = []
    line_count = 0
    longest_line = 0
    for path, content in files:
        lines = content.decode().splitlines()
        line_count += len(lines)
        longest_line = max(longest_line, max(map(len, lines), default=0))
        long_lines.extend({'file': path, 'line': number, 'length': len(line)}
                          for number, line in enumerate(lines, 1) if len(line) > 120)
        tree = parser.parse(content)
        assert not tree.root_node.has_error, path
        nodes = [tree.root_node]
        while nodes:
            node = nodes.pop()
            nodes.extend(node.children)
            if node.type in ('method_declaration', 'constructor_declaration'):
                name = node.child_by_field_name('name').text.decode()
                methods.append({'file': path, 'name': name,
                                'lines': node.end_point.row - node.start_point.row + 1})
            if node.type in ('variable_declarator', 'formal_parameter', 'catch_formal_parameter',
                             'enhanced_for_statement', 'record_component'):
                name = node.child_by_field_name('name')
                if name and len(name.text.decode()) == 1 and name.text != b'i':
                    single_letters.append({'file': path, 'name': name.text.decode(),
                                           'line': name.start_point.row + 1})
            if node.type == 'lambda_expression':
                parameters = node.child_by_field_name('parameters')
                if parameters:
                    names = [parameters] if parameters.type == 'identifier' else parameters.children
                    for name in names:
                        if name.type == 'identifier' and len(name.text.decode()) == 1 and name.text != b'i':
                            single_letters.append({'file': path, 'name': name.text.decode(),
                                                   'line': name.start_point.row + 1})
    return {'file_count': len(files), 'total_lines': line_count, 'longest_line': longest_line,
            'longest_method': max(methods, key=lambda method: method['lines']),
            'methods_over_40': [method for method in methods if method['lines'] > 40],
            'lines_over_120': long_lines, 'single_letter_variables': single_letters}

def files_at(revision, directory):
    if revision:
        names = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', revision, directory], cwd=ROOT).decode().splitlines()
        return [(name, subprocess.check_output(['git', 'show', revision + ':' + name], cwd=ROOT))
                for name in names if name.endswith('.java')]
    return [(str(path.relative_to(ROOT)), path.read_bytes()) for path in sorted((ROOT / directory).rglob('*.java'))]

if __name__ == '__main__':
    report = {label: {scope: measure(files_at(revision, directory))
                     for scope, directory in [('main', 'src/main/java'), ('test', 'src/test/java')]}
              for label, revision in [('before', '9b0aab1'), ('after', None)]}
    destination = ROOT / 'reports/evidence/v1.1/java-metrics.json'
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report['after'], ensure_ascii=False, indent=2))

    if '--check' in sys.argv:
        for scope in report['after'].values():
            assert not scope['methods_over_40'], 'Methods exceed 40 lines'
            assert not scope['lines_over_120'], 'Lines exceed 120 characters'
            assert not scope['single_letter_variables'], 'Single-letter variables found'

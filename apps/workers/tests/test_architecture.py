import ast
from pathlib import Path


def test_domain_does_not_import_infrastructure() -> None:
    domain_path = Path(__file__).parents[1] / "src" / "vcut_workers" / "domain"

    for source_path in domain_path.glob("*.py"):
        tree = ast.parse(source_path.read_text(encoding="utf-8"))
        imported_modules = {
            node.module
            for node in ast.walk(tree)
            if isinstance(node, ast.ImportFrom) and node.module is not None
        }

        assert not any("infrastructure" in module for module in imported_modules)

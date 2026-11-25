# Game-side test fixtures

`data/tianshu_ferry/structure/plaza.nbt` is the flat 3×3 stone platform every test in
`src/gametest/java` stands on. It is a binary, gzipped NBT structure template — the only shape
`StructureTemplateManager` loads from `data/<namespace>/structure/<name>.nbt` (SNBT is not read
from this path, which is why a readable copy is not kept here).

The platform is deliberately trivial: a test that needs a chest, a crafting table or a furnace
places those itself, so the fixture never becomes a hidden part of what a test proves.

To change it, write the structure you want in game, save it with a structure block, and copy the
`.nbt` over this one. A structure block save produces exactly this format, which keeps the fixture
regenerable by hand rather than by a bespoke generator that has to be maintained.
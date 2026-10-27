<!-- Built by ai/build.py from ai/brief.md, SPEC.md and examples/. Edit those, not this. -->

# xtemplate for AI assistants

This one file holds everything an AI chat assistant needs to write an `.xtemplate`: what to hand
back, how to make it look good, the complete format specification and worked examples. Paste
all of it into a chat, then describe the page template or cover template you want.

## To the assistant

The person who pasted this wants a template in the xtemplate format, which is newer than your
training data. Read the whole file before you answer. Everything you need is here, and nothing
outside it is part of the format. They describe what they want after this text or in their next
message. Where the request leaves something open, choose well instead of asking, and say in a
sentence what you chose.

### What to hand back

The person imports what you make as a file, so hand back a `.xtemplate` file to download, named
after the template, such as `habit-tracker.xtemplate`.

**A page template** (a ruling, grid, planner, music paper and so on) is plain JSON, and its file
holds that JSON alone.

- If you can run code, write the file and offer it for download:

  ```python
  import json

  with open("habit-tracker.xtemplate", "w", encoding="utf-8") as f:
      json.dump(template, f, indent=2)
  ```

- If you cannot create files, give the whole template in one ```` ```json ```` code block, with
  nothing else inside the block, and ask the person to save it as a text file whose name ends
  in `.xtemplate`.
- Draw a page template with lines, shapes and text only. Leave pictures out unless the person
  asks for one.

**A cover template** has `"kind": "cover"` and fills the whole page. A cover template with a
picture is a package (§1, Packages): a ZIP file named like `night-sky.xtemplate`, holding
`template.json` at its root and the picture under `images/`.

- For the picture, use the one the person attached, draw one with code (gradients, patterns,
  textures, generative art), or make one with an image tool if you have one. Store it upright as
  JPEG, PNG or WebP, about 1400 × 1850 pixels for a portrait page, and well under 16 MiB.
- If you can run code, build the package and offer the file for download:

  ```python
  import json, zipfile

  with zipfile.ZipFile("night-sky.xtemplate", "w") as z:
      z.writestr("template.json", json.dumps(template, indent=2), zipfile.ZIP_DEFLATED)
      z.write("night-sky.jpg", "images/night-sky.jpg", zipfile.ZIP_STORED)
  ```

- A picture made by an image tool may not be reachable from your code. Then show it, ask the
  person to save it and attach it again, and build the package once it arrives.
- If you cannot make files at all, give `template.json` in a ```` ```json ```` code block and
  explain the last step: put it in a ZIP with the picture in an `images` folder, then rename the
  `.zip` to `.xtemplate`.
- A cover template can also be drawn entirely in shapes, with no picture. It is then plain
  JSON, like a page template.

### Where it will be used

Notebook apps such as xnotes, a handwriting notebook for Android, import the file into a template
library. The template is drawn beneath handwriting on pages of any size and orientation (A4,
Letter, A5, a tablet screen, portrait or landscape) and on light or dark paper. Every parameter
becomes a control: a length or number becomes a slider, and a colour parameter a row of colours.

### Making it good

- Describe the design in proportions, not in positions fixed for one paper size: `"100% - 15mm"`,
  `min(W, H)`, `fit: "space"` or `"round"` for whole cells. Picture it on A4 portrait
  (210 × 297 mm), on A4 landscape (297 × 210 mm) and on a narrow page about 70 × 150 mm.
- In a page template, draw lines in `ink` and margins, headers and highlights in `accent`, never
  in fixed colours, so the template works on dark paper and in the person's own colours. Keep the
  default line width of 0.25 mm unless a heavier line means something, such as every fifth grid
  line.
- Leave room to write. Rulings usually start below a header and stop short of the page edges, and
  labels are small (`size` about 3 mm) and quiet.
- In a cover template, first fill the page with a `rect` in the cover's own colour, often a
  `color` parameter. Then draw the picture with `fit: "cover"` and a `position` that keeps its
  subject in view on a wide page, then any frame, and a label to write the title on. Use literal
  colours or `color` parameters in a cover template, not `ink`, so it looks the same on any
  paper.
- Make what a person would want to adjust into `params`, each with a short `label`: the main
  spacing as a `length` with `"role": "spacing"` and sensible `min` and `max`, counts as
  `integer`, colours as `color`.
- Give the template a `name`, a one-sentence `description` and two or three `tags`. Mark a cover
  template with `"kind": "cover"`, and credit its picture's source in the `description` when it
  has one.

### Before you reply

- The JSON parses: double quotes, no comments and no trailing commas.
- Every expression uses only the units, functions and names of §3: the parameters you declared,
  `W`, `H`, `i`, `j`, `pi` and the `index` names of an enclosing repeat.
- Every property and node type is one the specification defines. Readers ignore anything else
  without a warning, so an invented property does nothing at all.
- Every `image` `src` names a file in the package, and a plain JSON template has no `image`.
- The template stays within the limits of §11.

## xtemplate format, version 1

An `.xtemplate` file describes what is printed on a blank sheet of paper: the ruling of a page
(lines, grids, dots, music staves, planner layouts) or the design of a notebook's cover. It does
not describe one fixed picture. It describes how to draw the design on a page of **any** size
and aspect ratio, so a single file serves A4, Letter, a tablet screen or a custom page.

A template is plain data. It has no loops, no scripts and no references to anything outside its
own file; the pictures it draws travel inside it (§1, Packages). Every value is either a literal
or a small arithmetic expression, and the only repetition is the `repeat` node, whose count
follows from the page size. Evaluating a template always terminates, and its cost is bounded by
the limits in §11.

The key words MUST, MUST NOT, SHOULD and MAY are used as described in RFC 2119.

### 1. File

- Encoding: UTF-8 JSON (RFC 8259), or a package holding that JSON and pictures (below).
- Extension: `.xtemplate`, for both.
- The top-level value is an object.

```json
{
  "xtemplate": 1,
  "name": "Ruled",
  "items": [
    { "type": "repeat", "axis": "y", "period": "8mm", "children": [
      { "type": "hline", "y": "100%" }
    ]}
  ]
}
```

#### Top-level properties

| Property | Type | Required | Meaning |
|---|---|---|---|
| `xtemplate` | integer | yes | Format version. This document defines `1`. |
| `name` | string | yes | Display name. |
| `items` | array of nodes | yes | What to draw, in painting order (§5). |
| `id` | string | no | A stable identifier, so libraries can recognise a newer version of the same template. Reverse-DNS is recommended: `org.example.music-staff`. |
| `description` | string | no | One or two sentences for humans. |
| `author` | string | no | |
| `license` | string | no | An SPDX identifier, such as `CC0-1.0` or `MIT`. |
| `version` | string | no | The template's own version, such as `1.2`. |
| `tags` | array of strings | no | Free-form keywords for search: `music`, `planner`. |
| `kind` | string | no | What the template is for: `"page"`, paper for the pages of a document (the default), or `"cover"`, the design of a notebook's cover. |
| `params` | object | no | User-adjustable values (§4). |
| `comment` | string | no | Ignored. Also allowed on every node. |

The top level also accepts the style properties (§6). They act as defaults for every item.

`kind` only tells hosts where to offer a template, such as a list of page rulings or a list of
covers. It never changes how the template draws, and hosts MAY let the user apply a template of
either kind anywhere. An unknown value counts as `"page"`.

#### Packages

A template that draws pictures (`image`, §5.3) is a **package**: a ZIP archive with the same
`.xtemplate` extension.

- The template's JSON is the entry `template.json` at the root of the archive. Everything in
  this document applies to it unchanged.
- Each picture is another entry, at the path an `image` names in `src`. Putting pictures under
  `images/` is the convention. Entries that no `image` names are ignored.
- Pictures are PNG, JPEG or WebP. Readers recognise the format from the picture's content, not
  from its entry name. A picture is drawn as stored: readers ignore any EXIF orientation, so
  writers store pictures upright.
- Entries are stored or deflated, and not encrypted.

Readers tell the two forms apart by the first bytes of the file: a package starts with `PK`, and
plain JSON with `{`, possibly after whitespace or a byte order mark.

### 2. Coordinates and units

- The origin is the top-left corner of the page. `x` grows to the right, `y` grows downward.
- Every node is laid out inside a **box**. The box of the top-level items is the page. A
  `group` sets a box for its children, and a `repeat` gives each copy its own box, the cell.
- A length is a JSON number, which means millimetres, or a string holding an expression (§3).

#### Units

| Suffix | Meaning |
|---|---|
| none | millimetres |
| `mm` | millimetres |
| `cm` | 10 mm |
| `in` | 25.4 mm |
| `pt` | 1/72 in (0.3528 mm) |
| `%` | a fraction of the current box, see below |
| `deg` | degrees; the same as no suffix, allowed on angles for readability |

`%` depends on the property:

- **Horizontal lengths** (`x`, `x1`, `x2`, `cx`, `w`, `rx`, the left and right `inset`, a
  horizontal `period`, `stagger`): percent of the current box's width.
- **Vertical lengths** (`y`, `y1`, `y2`, `cy`, `h`, `ry`, the top and bottom `inset`, a
  vertical `period`): percent of the current box's height.
- **Other lengths** (`r`, `strokeWidth`, `size`, `dash`, a hatch `period` and `offset`):
  percent of the shorter side of the current box.
- **Plain numbers** (`opacity`, `count`, angles): `50%` is `0.5`.

A `group` or `repeat` measures its own properties (`x`, `y`, `w`, `h`, `inset`, `period`,
`stagger`) against the box it sits in. Its children are measured against the box it creates:
the group's box, or the cell.

### 3. Expressions

Any number or length may be written as a string expression:

```json
"x": "100% - 15mm"
"period": "gap * 10"
"r": "min(W, H) / 2 - 5mm"
```

#### Grammar

```
expr     = product { ("+" | "-") product }
product  = unary { ("*" | "/") unary }
unary    = [ "+" | "-" ] atom
atom     = number [ unit ] | name | name "(" [ expr { "," expr } ] ")" | "(" expr ")"
number   = digits [ "." digits ] | "." digits
unit     = "mm" | "cm" | "in" | "pt" | "%" | "deg"
name     = letter { letter | digit | "_" }        (letter includes "_")
```

Whitespace between tokens is ignored. A unit must follow its number directly: `2mm`, not
`2 mm`. An expression string is at most 1000 characters.

#### Values

Units are conversion factors. `2cm` evaluates to `20`, and `50%` to half the reference
dimension in millimetres. A bare number inside an expression is a plain number, and the final
result of a length expression is read in millimetres. So `"3"`, `"3mm"` and `3` mean the same
length, and `"gap * 10"` is ten gaps.

#### Names

| Name | Value |
|---|---|
| a parameter name | the parameter's current value (§4); a length in mm |
| `W`, `H` | width and height of the current box, in mm |
| `i` | index of the innermost enclosing `repeat` (column index for `axis: "xy"`); `0` outside any repeat |
| `j` | row index of the innermost enclosing `axis: "xy"` repeat; `0` otherwise |
| a repeat's `index` names | see §5.2 |
| `pi` | 3.14159... |

Names are case-sensitive.

#### Functions

`min(a, b, ...)`, `max(a, b, ...)`, `abs(x)`, `floor(x)`, `ceil(x)`, `round(x)` (halves round
away from zero), `mod(a, b)` (the result has the sign of `b`), `sqrt(x)`, `pow(a, b)`,
`sin(deg)`, `cos(deg)`, `tan(deg)`, `log10(x)`.

Trigonometric functions take degrees.

#### Errors

An expression that fails to parse, names something unknown, divides by zero, or produces NaN
or infinity makes the node that holds it invalid. An invalid node is skipped together with its
children. The rest of the template still draws.

### 4. Parameters

`params` maps a name to a definition. The host shows parameters as controls and passes their
values in; a parameter the host does not set takes its default.

```json
"params": {
  "gap":   { "type": "length", "default": "2mm", "min": "1.2mm", "max": "3mm",
             "label": "Line gap", "role": "spacing" },
  "rows":  { "type": "integer", "default": 7, "min": 1, "max": 24, "label": "Rows" },
  "frame": { "type": "color", "default": "#3a7bd5", "label": "Frame colour" }
}
```

| Property | Meaning |
|---|---|
| `type` | `length` (mm), `number`, `integer` or `color`. Required. |
| `default` | Required. A literal: a number, a length with a unit other than `%`, or a colour for `color`. |
| `min`, `max` | Optional literals for `length`, `number` and `integer`. Hosts MUST clamp values into this range. |
| `step` | Optional. A suggested increment for sliders. |
| `label` | Optional display name. Hosts fall back to the parameter name. |
| `role` | Optional. `spacing` marks the parameter the host's main spacing control drives. At most one parameter, of type `length`, may have it. |

- A parameter name follows the `name` grammar and MUST NOT be one of the built-in names
  (`W`, `H`, `i`, `j`, `pi`) or a function name. Such a parameter is ignored.
- Defaults and bounds cannot refer to other parameters, so there are no cycles.
- An `integer` value is rounded to the nearest integer after clamping.
- A `color` parameter is used by writing its name as a colour (§6).

### 5. Nodes

Every node is an object with a `type`. Two properties are allowed on every node and ignored by
renderers: `id` (a string, for authoring tools) and `comment`.

#### 5.1 `group`

Sets a box for its children, and shared style.

| Property | Default | Meaning |
|---|---|---|
| `x`, `y`, `w`, `h` | `0`, `0`, `100%`, `100%` | The group's box inside its parent's box. |
| `inset` | none | Shrinks the box from its edges. One to four lengths, in CSS order: `[all]`, `[vertical, horizontal]`, `[top, horizontal, bottom]` or `[top, right, bottom, left]`. A single value may be given without the array. |
| `clip` | `false` | When `true`, the children are clipped to the group's box (§7.2). |
| `children` | `[]` | Child nodes. |

Style properties (§6) on a group are inherited by its children.

```json
{ "type": "group", "inset": ["25mm", "0", "50mm", "30%"], "children": [ ... ] }
```

#### 5.2 `repeat`

Draws its children once per cell, filling its parent's box along one or both axes.

| Property | Default | Meaning |
|---|---|---|
| `axis` | `"y"` | `"y"` repeats downward in rows, `"x"` rightward in columns, `"xy"` in both, `"none"` in place (below). |
| `period` | none | The cell size along the axis. For `"xy"`, one length for both axes or `[x, y]`. |
| `count` | none | The number of cells. For `"xy"`, one integer for both axes or `[x, y]`. |
| `fit` | `"repeat"` | How cells fill the box, below. For `"xy"`, one value or `[x, y]`. |
| `stagger` | `0` | `"xy"` only: odd rows (`j` odd) shift right by this length. |
| `extend` | `false` | Only with `fit: "repeat"`: the cells continue across the whole paper (§8). |
| `index` | none | Names for the index variables in addition to `i` and `j`: a string for `"x"` and `"y"`, `[column, row]` for `"xy"`. |
| `children` | `[]` | Nodes drawn in every cell. |

At least one of `period` and `count` is required on each repeating axis. Along an axis with box
length `S`:

- **`count` only:** `n = count` cells of size `p = S / n`, the first at the box start.
- **`period`, `fit: "repeat"`:** cells of size `p = period` at `k * p` from the box start, for
  every integer `k` whose cell overlaps the box. With `count`, only `k = 0 ... count - 1`.
  A partial last cell is drawn and clipped.
- **`period`, `fit: "space"`:** `n = floor(S / p)` whole cells (at most `count`). When `n > 1`,
  the leftover `S - n * p` is shared equally between the cells, so the first cell touches the
  start of the box and the last touches the end. When `n = 1`, the cell is centred.
- **`period`, `fit: "round"`:** `n = max(1, round(S / p))` cells (exactly `count`, when given),
  stretched or squeezed to `p = S / n` so they fill the box exactly.
- **`period`, `fit: "center"`:** `n = floor(S / p)` whole cells (at most `count`), packed
  together and centred in the box.

When `n` is less than 1, the repeat draws nothing. `fit` has no effect on an axis that has
`count` but no `period`.

With `axis: "none"`, the children are drawn `count` times (required) in the repeat's own box,
which does not move; only `i` changes, from `0` to `count - 1`. This is for shapes placed by
formula, such as concentric circles (`"r": "(i + 1) * 10mm"`) or rays at `i * 15` degrees.
`period`, `fit`, `stagger` and `extend` have no effect.

Each copy's children are laid out in its cell: for `"y"`, the cell spans the box's full width
and one period of height; for `"x"`, the full height and one period of width; for `"xy"`, one
period in each direction. The index `i` counts cells along x for `"x"` and `"xy"`, and along y
for `"y"`, starting at `0` for the cell at the box start. `j` counts rows for `"xy"`. With
`fit: "repeat"`, cells before the box start (from `stagger` or `extend`) have negative indices.

A repeat always clips its cells to its parent's box, or to the paper when `extend` is true
(§7.2).

```json
{ "type": "repeat", "axis": "y", "period": "gap * 10", "fit": "space", "children": [
  { "type": "repeat", "axis": "none", "count": 5, "children": [
    { "type": "hline", "y": "gap * (3 + i)" }
  ]}
]}
```

#### 5.3 Shapes

Coordinates are relative to the current box. All shapes accept the style properties (§6).

| Type | Properties (defaults) | Draws |
|---|---|---|
| `line` | `x1`, `y1`, `x2`, `y2` (all `0`) | A straight line. |
| `hline` | `y` (`0`), `x1` (`0`), `x2` (`100%`) | A horizontal line, by default across the whole box. |
| `vline` | `x` (`0`), `y1` (`0`), `y2` (`100%`) | A vertical line, by default down the whole box. |
| `hatch` | `angle` (`0`), `period` (required), `offset` (`0`), `extend` (`false`) | Parallel lines filling the box, see below. |
| `rect` | `x`, `y` (`0`), `w`, `h` (`100%`), `rx` (`0`) | A rectangle, corners rounded by `rx`. |
| `circle` | `cx`, `cy` (`0`), `r` (required) | A circle. |
| `ellipse` | `cx`, `cy` (`0`), `rx`, `ry` (required) | An axis-aligned ellipse. |
| `dot` | `cx`, `cy` (`0`), `r` (`0.35mm`) | A filled dot, see §6. |
| `polyline` | `points` (required) | Connected straight segments. |
| `polygon` | `points` (required) | A closed polyline. |
| `path` | `d` (required), `viewBox`, `x`, `y` (`0`), `w`, `h` (`100%`), `aspect` (`"meet"`) | SVG path geometry, see below. |
| `text` | `text` (required), `x`, `y` (`0`), `select` (`"i"`) | A single line of text, see below. |
| `image` | `src` (required), `x`, `y` (`0`), `w`, `h` (`100%`), `fit` (`"contain"`), `position` (`"center"`) | A picture from the package, see below. |

**`hatch`** draws lines at `angle` degrees, measured counter-clockwise from horizontal as seen
on the page (so `60` rises to the right), spaced `period` apart measured perpendicular to the
lines. One line passes through the box's top-left corner moved `offset` along the
perpendicular. The lines fill the box and are clipped to it, or fill the whole paper when
`extend` is true (§8).

**`points`** is an array of `[x, y]` pairs, each coordinate a length, or a string of numbers in
mm in the SVG form `"0,0 10,5 20,0"`.

**`path`**: `d` is SVG path data (SVG 1.1 §8.3: `M L H V C S Q T A Z`, absolute and relative).
Without a `viewBox`, its numbers are millimetres relative to the current box, and `x`, `y`,
`w` and `h` are ignored. With a `viewBox`
(`"minX minY width height"`, or an array of four numbers), the viewBox is mapped onto the
rectangle `x, y, w, h`: `aspect: "meet"` scales it uniformly to fit and centres it,
`aspect: "stretch"` scales each axis to fill. Only `d` is supported, not whole SVG documents.

**`text`** draws one line, anchored at `x, y`.

- `{expr}` inside the string is replaced by the value of `expr` (§3). An integral value prints
  without decimals; anything else is rounded to at most three decimals with trailing zeros
  removed. `{expr:0N}` rounds to an integer and zero-pads it to `N` digits. `{{` and `}}` are
  literal braces. Example: `"{i + 8:02}:00"` gives `08:00`, `09:00`...
- `text` may be an array of strings. The element drawn is `mod(floor(select), length)`, where
  `select` is an expression, `i` by default. So
  `["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]` inside a 7-cell repeat labels each cell.
- Text properties are style properties (§6), so a group can set them for all its labels.

**`image`** draws the picture stored at `src`, a path inside the package (§1, Packages), in the
rectangle `x, y, w, h`.

- `fit: "contain"` scales the picture uniformly to fit inside the rectangle, `"cover"` scales it
  uniformly to cover the rectangle and crops what falls outside, and `"stretch"` scales each
  axis to fill the rectangle exactly.
- `position` says where the picture sits when `contain` leaves space or `cover` crops:
  `"center"`, `"top"`, `"bottom"`, `"left"`, `"right"`, `"top left"`, `"top right"`,
  `"bottom left"` or `"bottom right"`. An unknown value counts as `"center"`.
- `src` is compared exactly with the package's entry names. It never starts with `/` and has no
  `..` segment. An `image` whose picture is missing or cannot be decoded draws nothing, and so
  does every `image` in a plain JSON template.
- The picture keeps its own transparency, multiplied by the inherited `opacity`. `stroke`,
  `fill` and the text properties do not apply to it.
- To tile a picture, put an `image` inside a `repeat`.

### 6. Style

Style properties may appear on the top level, on `group` and `repeat`, and on any shape. Every
one is inherited by descendants until overridden.

| Property | Default | Meaning |
|---|---|---|
| `stroke` | `"ink"` | Colour of lines and outlines, or `"none"`. |
| `strokeWidth` | `0.25mm` | Line width. |
| `dash` | `"none"` | `[on, off]`: the lengths of each dash and each gap. |
| `fill` | `"none"` | Fill colour for `rect`, `circle`, `ellipse`, `polygon` and `path`, or `"none"`. |
| `opacity` | `1` | Multiplies the alpha of every colour and picture below it. Nested opacities multiply. |
| `font` | `"sans"` | `"sans"`, `"serif"` or `"mono"`. Hosts choose the actual typeface. |
| `size` | `3.5mm` | Font size (the em size). |
| `weight` | `"normal"` | `"normal"` or `"bold"`. |
| `italic` | `false` | |
| `align` | `"start"` | Horizontal anchor of text: `"start"`, `"middle"` or `"end"`. |
| `baseline` | `"alphabetic"` | Vertical anchor of text: `"alphabetic"` puts the baseline on the anchor, `"middle"` puts it 0.35 em below, `"top"` 0.7 em below (about the centre and the top of capital letters). |
| `rotate` | `0` | Text rotation about its anchor: `0`, `90`, `180` or `270`, clockwise. Other values round to the nearest quarter turn. |

`dot` and `text` are painted with `fill` when it is set and not `"none"`, and otherwise with
`stroke`. A `dot` has no outline. Fills use the nonzero winding rule. Hosts MAY draw text as
filled glyph outlines.

#### Colours

A colour is one of:

- **`"ink"`**: the host's pattern colour. Hosts SHOULD let the user choose it, and SHOULD
  default to a colour that reads well on the current paper, light or dark.
- **`"accent"`**: the host's second pattern colour, for margin lines, headers and highlights.
  Hosts SHOULD let the user choose it and SHOULD default to a red suited to margin lines.
- **Hex**: `#rgb`, `#rgba`, `#rrggbb` or `#rrggbbaa`.
- **A CSS named colour**, from the CSS Color Level 4 keyword list: `red`, `steelblue`...
- **The name of a `color` parameter.** A parameter wins over a CSS colour of the same name.
- **`"none"`**: nothing is drawn.

Literal colours are drawn as written, whatever the paper colour. Prefer `ink` and `accent` so
templates stay legible in dark mode. A colour the reader does not understand is treated as
`ink`.

### 7. Rendering

#### 7.1 Order and compositing

Nodes paint in document order: depth first, children after their parent, each repeat's cells
from the lowest index to the highest (row by row for `"xy"`).

A template is drawn in **one layer per distinct colour**. The alpha of each colour (after
`opacity`) is not applied to individual shapes: all shapes of a colour are painted opaque into
their layer, and the layer is composited onto the paper at that alpha. Each `image` is a layer
of its own. Layers composite in the order they first appear: a colour's layer where its first
shape is, an image's where the image is. As a result, where two lines of the same colour cross,
the crossing is no darker than the lines.

Templates never paint the paper itself; the paper colour belongs to the host. A template may
still fill shapes, such as shaded bands. A cover usually fills its whole page with a `rect` in a
colour of its own, often a `color` parameter, so it looks the same on any paper.

Strokes have round caps and round joins. A dash pattern starts at each line's first point.

#### 7.2 Clipping

Three things clip: the paper, every `repeat` (to its parent's box, or to the paper when
extended), and every `group` with `clip: true`. The clip in effect is the intersection of all
of these. It is always a rectangle and is applied to geometry, as follows:

- **Lines and outlines** (`line`, `hline`, `vline`, `hatch`, `polyline`, and the outlines of
  `rect`, `polygon` and `path`): the centreline is cut to the clip rectangle. The stroke is then
  drawn around what remains, so it may extend past the edge by half its width.
- **Fills** (`rect`, `polygon`, `path`) and **pictures** (`image`): the filled area, or the
  picture, is intersected with the clip rectangle.
- **`circle`, `ellipse`, `dot` and `text`** are drawn whole when their centre (for `text`, its
  anchor) is inside the clip rectangle, and not at all otherwise.
- **The paper's edge:** a piece of centreline lying exactly along an edge of the paper is
  dropped, and so is a `circle`, `ellipse`, `dot` or `text` centred exactly on one. On any other
  clip edge they are kept.

Curves in `path`, and rounded corners, may be flattened to line segments before clipping, with a
deviation of at most 0.05 mm.

These rules make output identical between renderers, including renderers that write PDF or SVG,
where there is no pixel clip to rely on. The paper-edge rule keeps a ruling from sitting
half-visible on the edge of the page: a line at `y: 0` of the first row is not drawn when that
row starts at the top of the paper.

### 8. Extra paper

A host may show more paper than the page: margins added around it, or a page that grows as the
user writes. The page box stays the same size, and the extra area is drawn as follows:

- A `repeat` with `fit: "repeat"` and `extend: true` continues across the whole paper, in both
  directions along each axis where it has a period and `fit: "repeat"`. Its cells keep their
  positions (the box start plus whole periods), so adding a margin extends the ruling outward
  instead of moving it. Across a one-axis repeat, each cell spans the paper's full width (for
  `"y"`) or height (for `"x"`) instead of the box's. Such a repeat is clipped to the paper
  rather than to its box. `count` has no effect on an extended axis.
- A `hatch` with `extend: true` fills the whole paper, its lines still anchored to its box.
- Everything else stays where the page box puts it. Clipping groups still clip.

Hosts without extra paper treat the paper as the page box.

### 9. Compatibility

- Readers MUST ignore properties they do not know.
- Readers MUST skip nodes whose `type` they do not know, including their children.
- Property names and node types starting with `x-` are reserved for extensions and are never
  used by future versions of this format.
- A later version of this format increases `xtemplate` only for changes that older readers
  cannot safely ignore. Readers SHOULD still try to draw a file with a higher version, using the
  rules they know.

### 10. Host responsibilities

- Supply the page size in millimetres, the `ink` and `accent` colours, and parameter values.
- Draw nothing outside the paper.
- Never fetch anything on behalf of a template. A template has no references outside its own
  file.

### 11. Limits

Readers MUST support templates up to these limits and MAY refuse anything beyond them:

| Limit | Value |
|---|---|
| Plain JSON file, or `template.json` in a package | 1 MiB |
| Package size | 16 MiB |
| Picture size | 8192 pixels on its longer side |
| Nesting depth of nodes | 32 |
| Total number of nodes | 10,000 |
| Expression length | 1000 characters |
| `path` data length | 100,000 characters |
| `count` per axis | 10,000 |

A `repeat` whose cells come out smaller than 0.1 mm along an axis, and a `hatch` whose `period`
is smaller than 0.1 mm, draw nothing. Readers MAY stop drawing a page after 200,000 shapes.
Readers MAY decode a picture at a lower resolution than it is stored at.

### 12. A complete example

A music staff that fits whole staves on any page:

```json
{
  "xtemplate": 1,
  "name": "Music staff",
  "license": "CC0-1.0",
  "params": {
    "gap": { "type": "length", "default": "2mm", "min": "1.2mm", "max": "3mm",
             "label": "Line gap", "role": "spacing" }
  },
  "items": [
    { "type": "group", "inset": ["15mm", "12mm"], "children": [
      { "type": "repeat", "axis": "y", "period": "gap * 10", "fit": "space", "children": [
        { "type": "repeat", "axis": "none", "count": 5, "children": [
          { "type": "hline", "y": "gap * (3 + i)" }
        ]}
      ]}
    ]}
  ]
}
```

Each staff sits in the middle of a ten-gap cell, and `fit: "space"` spreads as many whole cells
as fit down the page, so no staff is ever cut off at the bottom.

## Examples

Templates from the examples folder of the format's repository, unchanged.

### Ruled with margin, a page template

Lines below a header, an `accent` margin line and length parameters.

```json
{
  "xtemplate": 1,
  "id": "xtemplate.examples.ruled",
  "name": "Ruled with margin",
  "description": "Horizontal lines below a header, with a margin line on the left.",
  "license": "MIT",
  "tags": ["lines", "writing"],
  "params": {
    "spacing": { "type": "length", "default": "8mm", "min": "5mm", "max": "12mm", "label": "Line spacing", "role": "spacing" },
    "header": { "type": "length", "default": "24mm", "min": "0mm", "max": "60mm", "label": "Header height" },
    "margin": { "type": "length", "default": "28mm", "min": "0mm", "max": "60mm", "label": "Margin width" }
  },
  "items": [
    { "type": "group", "y": "header", "h": "100% - header", "children": [
      { "type": "repeat", "axis": "y", "period": "spacing", "children": [
        { "type": "hline", "y": 0 }
      ]}
    ]},
    { "type": "vline", "x": "margin", "stroke": "accent" }
  ]
}
```

### Dot grid, a page template

An `xy` repeat with `fit: "center"`, so the margins match on every side.

```json
{
  "xtemplate": 1,
  "id": "xtemplate.examples.dot-grid",
  "name": "Dot grid",
  "description": "Dots on a square grid, centred so the margins match on every side.",
  "license": "MIT",
  "tags": ["dots", "journal"],
  "params": {
    "size": { "type": "length", "default": "5mm", "min": "3mm", "max": "15mm", "label": "Dot spacing", "role": "spacing" }
  },
  "items": [
    { "type": "group", "inset": "6mm", "children": [
      { "type": "repeat", "axis": "xy", "period": "size", "fit": "center", "children": [
        { "type": "dot", "cx": "50%", "cy": "50%", "r": "0.3mm" }
      ]}
    ]}
  ]
}
```

### Cornell notes, a page template

Regions from expressions, a `number` parameter and small labels.

```json
{
  "xtemplate": 1,
  "id": "xtemplate.examples.cornell",
  "name": "Cornell notes",
  "description": "A cue column, a ruled note area and a summary box at the bottom.",
  "license": "MIT",
  "tags": ["lines", "study"],
  "params": {
    "spacing": { "type": "length", "default": "8mm", "min": "5mm", "max": "12mm", "label": "Line spacing", "role": "spacing" },
    "cue": { "type": "number", "default": 0.3, "min": 0.15, "max": 0.45, "step": 0.05, "label": "Cue column width" },
    "summary": { "type": "length", "default": "50mm", "min": "25mm", "max": "90mm", "label": "Summary height" }
  },
  "items": [
    { "type": "group", "stroke": "accent", "strokeWidth": "0.35mm", "children": [
      { "type": "hline", "y": "22mm" },
      { "type": "hline", "y": "100% - summary" },
      { "type": "vline", "x": "W * cue", "y1": "22mm", "y2": "100% - summary" }
    ]},
    { "type": "group", "x": "W * cue", "y": "22mm", "w": "100% - W * cue", "h": "100% - 22mm - summary", "children": [
      { "type": "repeat", "axis": "y", "period": "spacing", "children": [ { "type": "hline", "y": "100%" } ] }
    ]},
    { "type": "group", "y": "100% - summary", "h": "summary", "children": [
      { "type": "repeat", "axis": "y", "period": "spacing", "children": [ { "type": "hline", "y": "100%" } ] }
    ]},
    { "type": "group", "fill": "accent", "size": "3mm", "children": [
      { "type": "text", "text": "Cues", "x": "4mm", "y": "28mm" },
      { "type": "text", "text": "Notes", "x": "W * cue + 4mm", "y": "28mm" },
      { "type": "text", "text": "Summary", "x": "4mm", "y": "100% - summary + 6mm" }
    ]}
  ]
}
```

### Weekly planner, a page template

A `text` array picked by `select` labels each box of a repeat, with rounded `rect`s.

```json
{
  "xtemplate": 1,
  "id": "xtemplate.examples.weekly-planner",
  "name": "Weekly planner",
  "description": "Seven day boxes and a notes box in a two-column grid, each labelled and lightly ruled.",
  "license": "MIT",
  "tags": ["planner"],
  "items": [
    { "type": "group", "inset": ["14mm", "10mm", "10mm", "10mm"], "children": [
      { "type": "repeat", "axis": "xy", "count": [2, 4], "children": [
        { "type": "rect", "x": "2mm", "y": "2mm", "w": "100% - 4mm", "h": "100% - 4mm", "rx": "2mm" },
        { "type": "text", "text": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Notes"],
          "select": "j * 2 + i", "x": "6mm", "y": "9mm", "size": "3.5mm", "weight": "bold", "fill": "accent" },
        { "type": "group", "x": "6mm", "y": "12mm", "w": "100% - 12mm", "h": "100% - 16mm", "children": [
          { "type": "repeat", "axis": "y", "period": "7mm", "children": [ { "type": "hline", "y": "100%", "opacity": 0.5 } ] }
        ]}
      ]}
    ]}
  ]
}
```

### Daily planner, a page template

Hour labels formatted with `{start + i:02}:00`, tick boxes and `fit: "round"`.

```json
{
  "xtemplate": 1,
  "id": "xtemplate.examples.daily-planner",
  "name": "Daily planner",
  "description": "An hourly schedule with half-hour marks, beside a to-do list and a notes area.",
  "license": "MIT",
  "tags": ["planner"],
  "params": {
    "start": { "type": "integer", "default": 7, "min": 0, "max": 12, "label": "First hour" },
    "hours": { "type": "integer", "default": 14, "min": 6, "max": 18, "label": "Hours" }
  },
  "items": [
    { "type": "text", "text": "Date", "x": "12mm", "y": "16mm", "size": "3.2mm", "fill": "accent" },
    { "type": "hline", "y": "20mm", "x1": "12mm", "x2": "100% - 12mm", "strokeWidth": "0.35mm" },
    { "type": "group", "x": "12mm", "y": "28mm", "w": "62% - 12mm", "h": "100% - 40mm", "children": [
      { "type": "repeat", "axis": "y", "count": "hours", "children": [
        { "type": "hline", "y": 0 },
        { "type": "hline", "y": "50%", "x1": "14mm", "dash": ["1mm", "1mm"], "opacity": 0.5 },
        { "type": "text", "text": "{start + i:02}:00", "x": 0, "y": "4mm", "size": "3mm" }
      ]},
      { "type": "hline", "y": "100%" },
      { "type": "vline", "x": "12mm", "opacity": 0.5 }
    ]},
    { "type": "group", "x": "62% + 6mm", "y": "28mm", "w": "38% - 18mm", "h": "100% - 40mm", "children": [
      { "type": "text", "text": "To do", "x": 0, "y": "4mm", "size": "3.2mm", "fill": "accent" },
      { "type": "group", "y": "8mm", "h": "45% - 8mm", "children": [
        { "type": "repeat", "axis": "y", "period": "8mm", "fit": "round", "children": [
          { "type": "rect", "y": "2mm", "w": "4mm", "h": "4mm", "rx": "0.6mm" },
          { "type": "hline", "y": "6mm", "x1": "6mm" }
        ]}
      ]},
      { "type": "text", "text": "Notes", "x": 0, "y": "50% + 4mm", "size": "3.2mm", "fill": "accent" },
      { "type": "group", "y": "50% + 8mm", "h": "50% - 8mm", "children": [
        { "type": "repeat", "axis": "y", "period": "8mm", "children": [ { "type": "hline", "y": "100%" } ] }
      ]}
    ]}
  ]
}
```

### Almond Blossom, a cover template with a picture

The plainest cover template with a picture: a board colour, one `image` with `fit: "cover"`, and
a label to write the title on, drawn from arc `path`s in a `viewBox`, with `color` parameters
and a `labelSize` whose 0 hides it.

Its `template.json`, packaged with `images/almond-blossom.webp`:

```json
{
  "xtemplate": 1,
  "id": "xtemplate.examples.cover-almond-blossom",
  "name": "Almond Blossom",
  "description": "Almond Blossom by Vincent van Gogh, 1890, from the Van Gogh Museum, Amsterdam (public domain).",
  "license": "MIT",
  "kind": "cover",
  "params": {
    "label": { "type": "color", "default": "#f6f2e3", "label": "Label" },
    "rule": { "type": "color", "default": "#24484c", "label": "Label rule" },
    "labelSize": { "type": "number", "default": 1, "min": 0, "max": 1.4, "step": 0.05, "label": "Label size" }
  },
  "items": [
    { "type": "rect", "fill": "#6e8e84", "stroke": "none" },
    { "type": "image", "src": "images/almond-blossom.webp", "fit": "cover" },
    { "comment": "A bookplate label to write the title on. A label size of 0 hides it.",
      "type": "group", "x": "W / 2 - min(W, H) * labelSize * 0.29", "y": "H * 0.24 - min(W, H) * labelSize * 0.095", "w": "min(W, H) * labelSize * 0.58", "h": "min(W, H) * labelSize * 0.19",
      "opacity": "min(1, labelSize * 20)", "stroke": "none", "children": [
      { "type": "path", "viewBox": "0 0 290 95", "x": "0.5mm", "y": "0.8mm", "d": "M9 0 H281 A9 9 0 0 0 290 9 V86 A9 9 0 0 0 281 95 H9 A9 9 0 0 0 0 86 V9 A9 9 0 0 0 9 0 Z", "fill": "#0b262a4d" },
      { "type": "path", "viewBox": "0 0 290 95", "d": "M9 0 H281 A9 9 0 0 0 290 9 V86 A9 9 0 0 0 281 95 H9 A9 9 0 0 0 0 86 V9 A9 9 0 0 0 9 0 Z", "fill": "label" },
      { "type": "path", "viewBox": "0 0 290 95", "d": "M13.1 5 H276.9 A14 14 0 0 0 285 13.1 V81.9 A14 14 0 0 0 276.9 90 H13.1 A14 14 0 0 0 5 81.9 V13.1 A14 14 0 0 0 13.1 5 Z", "stroke": "rule", "strokeWidth": "0.35mm" }
    ]}
  ]
}
```

### Book Cloth, a cover template in any colour

The picture is light and shade only, white and black at partial opacity, tiled by an `xy`
`repeat` over a `rect` filled with a `color` parameter, so the person picks the cloth's colour.
A blind-stamped border is drawn with translucent strokes.

Its `template.json`, packaged with `images/cloth.webp`:

```json
{
  "xtemplate": 1,
  "id": "xtemplate.examples.cover-book-cloth",
  "name": "Book Cloth",
  "description": "Woven book cloth in any colour, with a blind-stamped border. The cloth is a CC0 scan by Rob Tuytel for Poly Haven.",
  "license": "MIT",
  "kind": "cover",
  "params": {
    "cloth": { "type": "color", "default": "#6e2a2c", "label": "Cloth" },
    "label": { "type": "color", "default": "#f1e8d4", "label": "Label" },
    "rule": { "type": "color", "default": "#3b2a26", "label": "Label rule" },
    "labelSize": { "type": "number", "default": 1, "min": 0, "max": 1.4, "step": 0.05, "label": "Label size" }
  },
  "items": [
    { "type": "rect", "fill": "cloth", "stroke": "none" },
    { "type": "repeat", "axis": "xy", "period": "300mm", "children": [
      { "type": "image", "src": "images/cloth.webp", "fit": "stretch" }
    ]},
    { "comment": "A blind-stamped border: a shadow line with a highlight just inside it.",
      "type": "group", "inset": "min(W, H) * 0.05", "stroke": "#00000052", "strokeWidth": "0.6mm", "children": [
      { "type": "rect", "rx": "1mm" },
      { "type": "rect", "x": "0.6mm", "y": "0.6mm", "w": "100% - 1.2mm", "h": "100% - 1.2mm", "rx": "0.6mm", "stroke": "#ffffff1f", "strokeWidth": "0.4mm" }
    ]},
    { "comment": "A bookplate label to write the title on. A label size of 0 hides it.",
      "type": "group", "x": "W / 2 - min(W, H) * labelSize * 0.29", "y": "H * 0.3 - min(W, H) * labelSize * 0.095", "w": "min(W, H) * labelSize * 0.58", "h": "min(W, H) * labelSize * 0.19",
      "opacity": "min(1, labelSize * 20)", "stroke": "none", "children": [
      { "type": "path", "viewBox": "0 0 290 95", "x": "0.5mm", "y": "0.8mm", "d": "M9 0 H281 A9 9 0 0 0 290 9 V86 A9 9 0 0 0 281 95 H9 A9 9 0 0 0 0 86 V9 A9 9 0 0 0 9 0 Z", "fill": "#00000047" },
      { "type": "path", "viewBox": "0 0 290 95", "d": "M9 0 H281 A9 9 0 0 0 290 9 V86 A9 9 0 0 0 281 95 H9 A9 9 0 0 0 0 86 V9 A9 9 0 0 0 9 0 Z", "fill": "label" },
      { "type": "path", "viewBox": "0 0 290 95", "d": "M13.1 5 H276.9 A14 14 0 0 0 285 13.1 V81.9 A14 14 0 0 0 276.9 90 H13.1 A14 14 0 0 0 5 81.9 V13.1 A14 14 0 0 0 13.1 5 Z", "stroke": "rule", "strokeWidth": "0.35mm" }
    ]}
  ]
}
```

## Your turn

That is the whole format. The person's request follows; write the template it asks for.

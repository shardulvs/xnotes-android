// Copyright 2014 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/jbig2/jbig2_huffman_table.h"

#include <algorithm>
#include <array>
#include <iterator>
#include <limits>

#include "core/fxcodec/jbig2/jbig2_bit_stream.h"
#include "core/fxcrt/check.h"
#include "core/fxcrt/check_op.h"
#include "core/fxcrt/fx_safe_types.h"

namespace {

constexpr JBig2TableLine kTableLine1[] = {{1, 4, 0},
                                          {2, 8, 16},
                                          {3, 16, 272},
                                          {0, 32, -1},
                                          {3, 32, 65808}};

constexpr JBig2TableLine kTableLine2[] = {{1, 0, 0},   {2, 0, 1},  {3, 0, 2},
                                          {4, 3, 3},   {5, 6, 11}, {0, 32, -1},
                                          {6, 32, 75}, {6, 0, 0}};

constexpr JBig2TableLine kTableLine3[] = {
    {8, 8, -256}, {1, 0, 0},     {2, 0, 1},   {3, 0, 2}, {4, 3, 3},
    {5, 6, 11},   {8, 32, -257}, {7, 32, 75}, {6, 0, 0}};

constexpr JBig2TableLine kTableLine4[] = {{1, 0, 1},  {2, 0, 2},  {3, 0, 3},
                                          {4, 3, 4},  {5, 6, 12}, {0, 32, -1},
                                          {5, 32, 76}};

constexpr JBig2TableLine kTableLine5[] = {{7, 8, -255},  {1, 0, 1},  {2, 0, 2},
                                          {3, 0, 3},     {4, 3, 4},  {5, 6, 12},
                                          {7, 32, -256}, {6, 32, 76}};

constexpr JBig2TableLine kTableLine6[] = {
    {5, 10, -2048}, {4, 9, -1024}, {4, 8, -512},   {4, 7, -256}, {5, 6, -128},
    {5, 5, -64},    {4, 5, -32},   {2, 7, 0},      {3, 7, 128},  {3, 8, 256},
    {4, 9, 512},    {4, 10, 1024}, {6, 32, -2049}, {6, 32, 2048}};

constexpr JBig2TableLine kTableLine7[] = {
    {4, 9, -1024}, {3, 8, -512}, {4, 7, -256},  {5, 6, -128},   {5, 5, -64},
    {4, 5, -32},   {4, 5, 0},    {5, 5, 32},    {5, 6, 64},     {4, 7, 128},
    {3, 8, 256},   {3, 9, 512},  {3, 10, 1024}, {5, 32, -1025}, {5, 32, 2048}};

constexpr JBig2TableLine kTableLine8[] = {
    {8, 3, -15}, {9, 1, -7},  {8, 1, -5},   {9, 0, -3},   {7, 0, -2},
    {4, 0, -1},  {2, 1, 0},   {5, 0, 2},    {6, 0, 3},    {3, 4, 4},
    {6, 1, 20},  {4, 4, 22},  {4, 5, 38},   {5, 6, 70},   {5, 7, 134},
    {6, 7, 262}, {7, 8, 390}, {6, 10, 646}, {9, 32, -16}, {9, 32, 1670},
    {2, 0, 0}};

constexpr JBig2TableLine kTableLine9[] = {
    {8, 4, -31},   {9, 2, -15}, {8, 2, -11}, {9, 1, -7},    {7, 1, -5},
    {4, 1, -3},    {3, 1, -1},  {3, 1, 1},   {5, 1, 3},     {6, 1, 5},
    {3, 5, 7},     {6, 2, 39},  {4, 5, 43},  {4, 6, 75},    {5, 7, 139},
    {5, 8, 267},   {6, 8, 523}, {7, 9, 779}, {6, 11, 1291}, {9, 32, -32},
    {9, 32, 3339}, {2, 0, 0}};

constexpr JBig2TableLine kTableLine10[] = {
    {7, 4, -21}, {8, 0, -5},    {7, 0, -4},    {5, 0, -3},   {2, 2, -2},
    {5, 0, 2},   {6, 0, 3},     {7, 0, 4},     {8, 0, 5},    {2, 6, 6},
    {5, 5, 70},  {6, 5, 102},   {6, 6, 134},   {6, 7, 198},  {6, 8, 326},
    {6, 9, 582}, {6, 10, 1094}, {7, 11, 2118}, {8, 32, -22}, {8, 32, 4166},
    {2, 0, 0}};

constexpr JBig2TableLine kTableLine11[] = {
    {1, 0, 1},  {2, 1, 2},  {4, 0, 4},  {4, 1, 5},   {5, 1, 7},
    {5, 2, 9},  {6, 2, 13}, {7, 2, 17}, {7, 3, 21},  {7, 4, 29},
    {7, 5, 45}, {7, 6, 77}, {0, 32, 0}, {7, 32, 141}};

constexpr JBig2TableLine kTableLine12[] = {
    {1, 0, 1},  {2, 0, 2},  {3, 1, 3},  {5, 0, 5},  {5, 1, 6},
    {6, 1, 8},  {7, 0, 10}, {7, 1, 11}, {7, 2, 13}, {7, 3, 17},
    {7, 4, 25}, {8, 5, 41}, {0, 32, 0}, {8, 32, 73}};

constexpr JBig2TableLine kTableLine13[] = {
    {1, 0, 1},  {3, 0, 2},  {4, 0, 3},  {5, 0, 4},   {4, 1, 5},
    {3, 3, 7},  {6, 1, 15}, {6, 2, 17}, {6, 3, 21},  {6, 4, 29},
    {6, 5, 45}, {7, 6, 77}, {0, 32, 0}, {7, 32, 141}};

constexpr JBig2TableLine kTableLine14[] = {{3, 0, -2}, {3, 0, -1}, {1, 0, 0},
                                           {3, 0, 1},  {3, 0, 2},  {0, 32, -3},
                                           {0, 32, 3}};

constexpr JBig2TableLine kTableLine15[] = {
    {7, 4, -24}, {6, 2, -8},   {5, 1, -4}, {4, 0, -2}, {3, 0, -1},
    {1, 0, 0},   {3, 0, 1},    {4, 0, 2},  {5, 1, 3},  {6, 2, 5},
    {7, 4, 9},   {7, 32, -25}, {7, 32, 25}};

constexpr std::array<const HuffmanTable, 16> kHuffmanTables = {{
    {false, {}},  // Zero dummy to preserve indexing.
    {false, kTableLine1},
    {true, kTableLine2},
    {true, kTableLine3},
    {false, kTableLine4},
    {false, kTableLine5},
    {false, kTableLine6},
    {false, kTableLine7},
    {true, kTableLine8},
    {true, kTableLine9},
    {true, kTableLine10},
    {false, kTableLine11},
    {false, kTableLine12},
    {false, kTableLine13},
    {false, kTableLine14},
    {false, kTableLine15},
}};

static_assert(CJBig2_HuffmanTable::kNumHuffmanTables ==
                  std::size(kHuffmanTables),
              "kNumHuffmanTables must be equal to the size of kHuffmanTables");

}  // namespace

CJBig2_HuffmanTable::CJBig2_HuffmanTable(size_t idx) {
  ok_ = ParseFromTable(kHuffmanTables[idx]);
  DCHECK(ok_);
}

CJBig2_HuffmanTable::CJBig2_HuffmanTable(CJBig2_BitStream* pStream) {
  ok_ = ParseFromCodedBuffer(pStream);
}

CJBig2_HuffmanTable::CJBig2_HuffmanTable(pdfium::span<uint8_t> prefix_lengths) {
  std::vector<JBig2TableLine> lines(prefix_lengths.size() + 2);
  for (size_t i = 0; const auto& length : prefix_lengths) {
    lines[i].PREFLEN = length;
    lines[i].RANGELEN = 0;
    lines[i].RANGELOW = static_cast<int>(i);
    ++i;
  }
  // Dummy open-ended intervals:
  lines[prefix_lengths.size()] = {0, 0, 0};
  lines[prefix_lengths.size() + 1] = {0, 0, 0};

  ok_ = ParseFromTable({.HTOOB = false, .lines = lines});
}

CJBig2_HuffmanTable::CJBig2_HuffmanTable() noexcept = default;
CJBig2_HuffmanTable::CJBig2_HuffmanTable(CJBig2_HuffmanTable&&) noexcept =
    default;
CJBig2_HuffmanTable& CJBig2_HuffmanTable::operator=(
    CJBig2_HuffmanTable&&) noexcept = default;
CJBig2_HuffmanTable::~CJBig2_HuffmanTable() = default;

std::optional<uint32_t> CJBig2_HuffmanTable::FindLine(unsigned codelen,
                                                      uint32_t code) const {
  if (codelen > max_codelen_ || code < first_codes_[codelen]) {
    return std::nullopt;
  }
  const uint32_t rank = code - first_codes_[codelen];
  if (rank >= code_counts_[codelen]) {
    return std::nullopt;
  }
  return lines_by_length_[first_line_indices_[codelen] + rank];
}

bool CJBig2_HuffmanTable::ParseFromTable(const HuffmanTable& table) {
  HTOOB = table.HTOOB;
  NTEMP = static_cast<uint32_t>(table.lines.size());
  pdfium::span<const JBig2TableLine> lines = table.lines;
  CODES.resize(lines.size());
  RANGELEN.resize(lines.size());
  RANGELOW.resize(lines.size());
  for (size_t i = 0; const auto& line : lines) {
    CODES[i].codelen = line.PREFLEN;
    RANGELEN[i] = line.RANGELEN;
    RANGELOW[i] = line.RANGELOW;
    ++i;
  }
  return AssignCodesAndBuildIndex();
}

bool CJBig2_HuffmanTable::ParseFromCodedBuffer(CJBig2_BitStream* pStream) {
  unsigned char cTemp;
  if (pStream->read1Byte(&cTemp) == -1) {
    return false;
  }

  HTOOB = !!(cTemp & 0x01);
  unsigned char HTPS = ((cTemp >> 1) & 0x07) + 1;
  unsigned char HTRS = ((cTemp >> 4) & 0x07) + 1;
  uint32_t HTLOW;
  uint32_t HTHIGH;
  if (pStream->readInteger(&HTLOW) == -1 ||
      pStream->readInteger(&HTHIGH) == -1) {
    return false;
  }

  const int low = static_cast<int>(HTLOW);
  const int high = static_cast<int>(HTHIGH);
  if (low > high) {
    return false;
  }

  ExtendBuffers(false);
  FX_SAFE_INT32 cur_low = low;
  do {
    if ((pStream->readNBits(HTPS, &CODES[NTEMP].codelen) == -1) ||
        (pStream->readNBits(HTRS, &RANGELEN[NTEMP]) == -1) ||
        (static_cast<size_t>(RANGELEN[NTEMP]) >= 8 * sizeof(cur_low))) {
      return false;
    }
    RANGELOW[NTEMP] = cur_low.ValueOrDie();

    if (RANGELEN[NTEMP] >= 32) {
      return false;
    }

    cur_low += (1 << RANGELEN[NTEMP]);
    if (!cur_low.IsValid()) {
      return false;
    }
    ExtendBuffers(true);
  } while (cur_low.ValueOrDie() < high);

  if (pStream->readNBits(HTPS, &CODES[NTEMP].codelen) == -1) {
    return false;
  }

  RANGELEN[NTEMP] = 32;
  if (low == std::numeric_limits<int>::min()) {
    return false;
  }

  RANGELOW[NTEMP] = low - 1;
  ExtendBuffers(true);

  if (pStream->readNBits(HTPS, &CODES[NTEMP].codelen) == -1) {
    return false;
  }

  RANGELEN[NTEMP] = 32;
  RANGELOW[NTEMP] = high;
  ExtendBuffers(true);

  if (HTOOB) {
    if (pStream->readNBits(HTPS, &CODES[NTEMP].codelen) == -1) {
      return false;
    }

    ++NTEMP;
  }

  return AssignCodesAndBuildIndex();
}

bool CJBig2_HuffmanTable::AssignCodesAndBuildIndex() {
  max_codelen_ = 0;
  for (const auto& code : CODES) {
    max_codelen_ = std::max(code.codelen, max_codelen_);
  }
  const size_t table_size = static_cast<size_t>(max_codelen_) + 1;
  code_counts_.assign(table_size, 0);
  for (const auto& code : CODES) {
    ++code_counts_[code.codelen];
  }
  code_counts_[0] = 0;

  first_codes_.assign(table_size, 0);
  first_line_indices_.assign(table_size, 0);
  FX_SAFE_UINT32 first_code = 0;
  uint32_t next_line_index = 0;

  for (unsigned codelen = 1; codelen <= max_codelen_; ++codelen) {
    first_code += code_counts_[codelen - 1];
    first_code <<= 1;
    if (!first_code.IsValid()) {
      return false;
    }
    first_codes_[codelen] = first_code.ValueOrDie();
    first_line_indices_[codelen] = next_line_index;
    next_line_index += code_counts_[codelen];
  }

  // One pass over the lines assigns the codes and fills the index, with
  // `filled` as the per length cursor.
  lines_by_length_.resize(next_line_index);
  std::vector<uint32_t> filled(table_size);
  for (size_t i = 0; i < CODES.size(); ++i) {
    const uint32_t codelen = CODES[i].codelen;
    if (codelen == 0) {
      continue;
    }
    CODES[i].code = first_codes_[codelen] + filled[codelen];
    lines_by_length_[first_line_indices_[codelen] + filled[codelen]] =
        static_cast<uint32_t>(i);
    ++filled[codelen];
  }
  return true;
}

void CJBig2_HuffmanTable::ExtendBuffers(bool increment) {
  if (increment) {
    ++NTEMP;
  }

  size_t size = CODES.size();
  if (NTEMP < size) {
    return;
  }

  size += 16;
  DCHECK_LT(NTEMP, size);
  CODES.resize(size);
  RANGELEN.resize(size);
  RANGELOW.resize(size);
}

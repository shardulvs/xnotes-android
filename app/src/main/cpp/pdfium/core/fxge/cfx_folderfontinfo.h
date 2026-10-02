// Copyright 2016 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#ifndef CORE_FXGE_CFX_FOLDERFONTINFO_H_
#define CORE_FXGE_CFX_FOLDERFONTINFO_H_

#include <map>
#include <memory>
#include <vector>

#include "core/fxcrt/fx_codepage_forward.h"
#include "core/fxcrt/mask.h"
#include "core/fxge/cfx_fontmapper.h"
#include "core/fxge/systemfontinfo_iface.h"

class CFX_FolderFontInfo : public SystemFontInfoIface {
 public:
  static bool FindFamilyNameMatch(ByteStringView family_name,
                                  const ByteString& installed_font_name);
  static ByteString ReadStringFromFile(FILE* pFile, uint32_t size);

  CFX_FolderFontInfo();
  ~CFX_FolderFontInfo() override;

  void AddPath(const ByteString& path);

  // SystemFontInfoIface:
  void EnumFontList(CFX_FontMapper* pMapper) override;
  void* MapFont(CFX_FontMapper* mapper,
                int weight,
                bool italic,
                FX_Charset charset,
                int pitch_family,
                const ByteString& face) override;
  void* GetFont(const ByteString& face) override;
  size_t GetFontData(void* hFont,
                     uint32_t table,
                     pdfium::span<uint8_t> buffer) override;
  void DeleteFont(void* hFont) override;
  bool GetFaceName(void* hFont, ByteString* name) override;
  bool GetFontCharset(void* hFont, FX_Charset* charset) override;

 protected:
  friend class CFXFolderFontInfoTest;

  class FontFaceInfo {
   public:
    static constexpr int32_t kSimilarityScoreMax = 68;

    FontFaceInfo(ByteString filePath,
                 ByteString faceName,
                 ByteString fontTables,
                 uint32_t fontOffset,
                 uint32_t fileSize);

    bool IsEligibleForFindFont(FX_CharsetFlag flag, FX_Charset charset) const;
    int32_t SimilarityScore(int weight,
                            bool italic,
                            int pitch_family,
                            bool exact_match_bonus) const;

    const ByteString file_path_;
    const ByteString face_name_;
    const ByteString font_tables_;
    const uint32_t font_offset_;
    const uint32_t file_size_;
    uint32_t styles_ = 0;
    Mask<FX_CharsetFlag> charsets_;
    uint32_t glyph_count_ = 0;
  };

  virtual bool IsBetterMatch(const FontFaceInfo* candidate,
                             int32_t candidate_score,
                             const FontFaceInfo* current_best,
                             int32_t current_best_score,
                             FX_Charset charset,
                             const ByteString& family,
                             bool must_match_name) const;

  void ScanPath(CFX_FontMapper* mapper, const ByteString& path);
  void ScanFile(CFX_FontMapper* mapper, const ByteString& path);
  void ReportFace(CFX_FontMapper* mapper,
                  const ByteString& path,
                  FILE* pFile,
                  FX_FILESIZE filesize,
                  uint32_t offset);
  void* GetSubstFont(const ByteString& face);
  void* FindFont(int weight,
                 bool italic,
                 FX_Charset charset,
                 int pitch_family,
                 const ByteString& family,
                 bool must_match_name);

  std::map<ByteString, std::unique_ptr<FontFaceInfo>> font_list_;
  std::vector<ByteString> path_list_;
};

#endif  // CORE_FXGE_CFX_FOLDERFONTINFO_H_

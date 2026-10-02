// Copyright 2020 The PDFium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Original code copyright 2014 Foxit Software Inc. http://www.foxitsoftware.com

#include "core/fxcodec/jpeg/jpeg_common.h"
#include "core/fxcrt/compiler_specific.h"

// This is a thin C wrapper around the JPEG API to avoid calling setjmp() from
// C++ code.

boolean jpeg_common_create_decompress(JpegCommon* jpeg_common) {
  if (setjmp(jpeg_common->jmpbuf) == -1) {
    return FALSE;
  }
  jpeg_create_decompress(&jpeg_common->cinfo);
  return TRUE;
}

void jpeg_common_destroy_decompress(JpegCommon* jpeg_common) {
  jpeg_destroy_decompress(&jpeg_common->cinfo);
}

boolean jpeg_common_start_decompress(JpegCommon* jpeg_common) {
  if (setjmp(jpeg_common->jmpbuf) == -1) {
    return FALSE;
  }
  return jpeg_start_decompress(&jpeg_common->cinfo);
}

int jpeg_common_read_header(JpegCommon* jpeg_common, boolean flag) {
  if (setjmp(jpeg_common->jmpbuf) == -1) {
    return -1;
  }
  return jpeg_read_header(&jpeg_common->cinfo, flag);
}

int jpeg_common_read_scanlines(JpegCommon* jpeg_common,
                               void* buf,
                               unsigned int count) {
  if (setjmp(jpeg_common->jmpbuf) == -1) {
    return -1;
  }
  return jpeg_read_scanlines(&jpeg_common->cinfo, buf, count);
}

void jpeg_common_src_do_nothing(j_decompress_ptr cinfo) {}

boolean jpeg_common_src_fill_buffer(j_decompress_ptr cinfo) {
  return FALSE;
}

boolean jpeg_common_src_resync(j_decompress_ptr cinfo, int desired) {
  return FALSE;
}

void jpeg_common_src_skip_data_or_record(j_decompress_ptr cinfo, long num) {
  if (cinfo->src->bytes_in_buffer < (size_t)num) {
    JpegCommon* pCommon = (JpegCommon*)(cinfo->client_data);
    pCommon->skip_size = (unsigned int)(num - cinfo->src->bytes_in_buffer);
    cinfo->src->bytes_in_buffer = 0;
  } else {
    // SAFETY: required from library during callback.
    UNSAFE_BUFFERS(cinfo->src->next_input_byte += num);
    cinfo->src->bytes_in_buffer -= num;
  }
}

void jpeg_common_src_skip_data_or_trap(j_decompress_ptr cinfo, long num) {
  if (num > (long)cinfo->src->bytes_in_buffer) {
    jpeg_common_error_fatal((j_common_ptr)cinfo);
  }
  // SAFETY: required from library API as checked above.
  UNSAFE_BUFFERS(cinfo->src->next_input_byte += num);
  cinfo->src->bytes_in_buffer -= num;
}

void jpeg_common_error_do_nothing(j_common_ptr cinfo) {}

void jpeg_common_error_do_nothing_int(j_common_ptr cinfo, int arg) {}

void jpeg_common_error_do_nothing_char(j_common_ptr cinfo, char* arg) {}

void jpeg_common_error_fatal(j_common_ptr cinfo) {
  JpegCommon* pCommon = (JpegCommon*)(cinfo->client_data);
  longjmp(pCommon->jmpbuf, -1);
}

#if BUILDFLAG(IS_WIN)
boolean jpeg_common_create_compress(JpegCompressCommon* jpeg_compress) {
  if (setjmp(jpeg_compress->jmpbuf) == -1) {
    return FALSE;
  }
  jpeg_compress->cinfo.err = &jpeg_compress->error_mgr;
  jpeg_compress->cinfo.client_data = jpeg_compress;
  jpeg_compress->error_mgr.error_exit = jpeg_common_error_fatal;
  jpeg_compress->error_mgr.emit_message = jpeg_common_error_do_nothing_int;
  jpeg_compress->error_mgr.output_message = jpeg_common_error_do_nothing;
  jpeg_compress->error_mgr.format_message = jpeg_common_error_do_nothing_char;
  jpeg_compress->error_mgr.reset_error_mgr = jpeg_common_error_do_nothing;
  jpeg_create_compress(&jpeg_compress->cinfo);
  jpeg_compress->dest_mgr.init_destination = jpeg_common_dest_do_nothing;
  jpeg_compress->dest_mgr.term_destination = jpeg_common_dest_do_nothing;
  jpeg_compress->dest_mgr.empty_output_buffer = jpeg_common_dest_empty;
  jpeg_compress->cinfo.dest = &jpeg_compress->dest_mgr;
  return TRUE;
}

void jpeg_common_destroy_compress(JpegCompressCommon* jpeg_compress) {
  jpeg_destroy_compress(&jpeg_compress->cinfo);
}

boolean jpeg_common_set_defaults(JpegCompressCommon* jpeg_compress) {
  if (setjmp(jpeg_compress->jmpbuf) == -1) {
    return FALSE;
  }
  jpeg_set_defaults(&jpeg_compress->cinfo);
  return TRUE;
}

boolean jpeg_common_start_compress(JpegCompressCommon* jpeg_compress,
                                   boolean write_all_tables) {
  if (setjmp(jpeg_compress->jmpbuf) == -1) {
    return FALSE;
  }
  jpeg_start_compress(&jpeg_compress->cinfo, write_all_tables);
  return TRUE;
}

int jpeg_common_write_scanlines(JpegCompressCommon* jpeg_compress,
                                JSAMPROW* scanlines,
                                unsigned int num_lines) {
  if (setjmp(jpeg_compress->jmpbuf) == -1) {
    return -1;
  }
  return (int)jpeg_write_scanlines(&jpeg_compress->cinfo, scanlines, num_lines);
}

boolean jpeg_common_finish_compress(JpegCompressCommon* jpeg_compress) {
  if (setjmp(jpeg_compress->jmpbuf) == -1) {
    return FALSE;
  }
  jpeg_finish_compress(&jpeg_compress->cinfo);
  return TRUE;
}

void jpeg_common_dest_do_nothing(j_compress_ptr cinfo) {}

boolean jpeg_common_dest_empty(j_compress_ptr cinfo) {
  return FALSE;
}
#endif  // BUILDFLAG(IS_WIN)

package com.cosmos.screensaver.screensaver

/**
 * Builds one H.264 picture made only of uncompressed (I_PCM) macroblocks: Baseline profile,
 * 4:2:0, BT.709 limited range. No encoder is involved, so any size the platform decoder accepts
 * can be produced exactly, pixel for pixel. Used to put a still picture on the TV's video plane.
 */
internal object PcmH264 {
    class Stream(val sps: ByteArray, val pps: ByteArray, val slice: ByteArray)

    /** NAL unit payload writer: bit-level, with start-code emulation prevention applied as it goes. */
    private class NalWriter(capacity: Int) {
        var buffer = ByteArray(capacity)
            private set
        var size = 0
            private set
        private var zeros = 0
        private var current = 0
        private var count = 0

        private fun put(value: Int) {
            if (size + 2 > buffer.size) buffer = buffer.copyOf(buffer.size + buffer.size / 2 + 16)
            if (zeros >= 2 && value <= 3) {
                buffer[size++] = 3
                zeros = 0
            }
            buffer[size++] = value.toByte()
            zeros = if (value == 0) zeros + 1 else 0
        }

        fun raw(value: Int) {
            if (size + 1 > buffer.size) buffer = buffer.copyOf(buffer.size + 16)
            buffer[size++] = value.toByte()
        }

        fun bit(b: Int) {
            current = (current shl 1) or (b and 1)
            if (++count == 8) {
                put(current)
                current = 0
                count = 0
            }
        }

        fun bits(value: Int, n: Int) {
            for (i in n - 1 downTo 0) bit((value ushr i) and 1)
        }

        fun ue(value: Int) {
            val v = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(v)
            bits(0, length - 1)
            bits(v, length)
        }

        fun se(value: Int) = ue(if (value <= 0) -2 * value else 2 * value - 1)

        fun alignZero() {
            while (count != 0) bit(0)
        }

        fun bytes(data: ByteArray, from: Int, length: Int) {
            check(count == 0)
            for (i in from until from + length) put(data[i].toInt() and 0xFF)
        }

        fun trailing() {
            bit(1)
            alignZero()
        }

        fun toArray(): ByteArray = buffer.copyOf(size)
    }

    private fun header(type: Int, capacity: Int): NalWriter = NalWriter(capacity).apply {
        raw(0); raw(0); raw(0); raw(1)
        raw((3 shl 5) or type)
    }

    /** [y] is width x height; [cb] and [cr] are (width / 2) x (height / 2). Width and height must be multiples of 16. */
    fun frame(width: Int, height: Int, y: ByteArray, cb: ByteArray, cr: ByteArray): Stream {
        require(width > 0 && height > 0 && width % 16 == 0 && height % 16 == 0) { "size must be a multiple of 16" }
        require(y.size >= width * height && cb.size >= width * height / 4 && cr.size >= width * height / 4)
        val mbW = width / 16
        val mbH = height / 16

        val sps = header(NAL_SPS, 64).apply {
            bits(66, 8) // profile_idc: Baseline
            bits(0b11000000, 8) // constraint_set0_flag, constraint_set1_flag
            bits(LEVEL_5_1, 8)
            ue(0) // seq_parameter_set_id
            ue(0) // log2_max_frame_num_minus4
            ue(2) // pic_order_cnt_type
            ue(1) // max_num_ref_frames
            bit(0) // gaps_in_frame_num_value_allowed_flag
            ue(mbW - 1)
            ue(mbH - 1)
            bit(1) // frame_mbs_only_flag
            bit(1) // direct_8x8_inference_flag
            bit(0) // frame_cropping_flag
            bit(1) // vui_parameters_present_flag
            bit(0) // aspect_ratio_info_present_flag
            bit(0) // overscan_info_present_flag
            bit(1) // video_signal_type_present_flag
            bits(5, 3) // video_format: unspecified
            bit(0) // video_full_range_flag: limited
            bit(1) // colour_description_present_flag
            bits(1, 8) // colour_primaries: BT.709
            bits(1, 8) // transfer_characteristics: BT.709
            bits(1, 8) // matrix_coefficients: BT.709
            bit(0) // chroma_loc_info_present_flag
            bit(0) // timing_info_present_flag
            bit(0) // nal_hrd_parameters_present_flag
            bit(0) // vcl_hrd_parameters_present_flag
            bit(0) // pic_struct_present_flag
            bit(0) // bitstream_restriction_flag
            trailing()
        }
        val pps = header(NAL_PPS, 32).apply {
            ue(0) // pic_parameter_set_id
            ue(0) // seq_parameter_set_id
            bit(0) // entropy_coding_mode_flag: CAVLC
            bit(0) // bottom_field_pic_order_in_frame_present_flag
            ue(0) // num_slice_groups_minus1
            ue(0) // num_ref_idx_l0_default_active_minus1
            ue(0) // num_ref_idx_l1_default_active_minus1
            bit(0) // weighted_pred_flag
            bits(0, 2) // weighted_bipred_idc
            se(0) // pic_init_qp_minus26
            se(0) // pic_init_qs_minus26
            se(0) // chroma_qp_index_offset
            bit(1) // deblocking_filter_control_present_flag
            bit(0) // constrained_intra_pred_flag
            bit(0) // redundant_pic_cnt_present_flag
            trailing()
        }
        val slice = header(NAL_IDR, sliceCapacity(width, height)).apply {
            ue(0) // first_mb_in_slice
            ue(7) // slice_type: I, all slices of the picture
            ue(0) // pic_parameter_set_id
            bits(0, 4) // frame_num
            ue(0) // idr_pic_id
            bit(0) // no_output_of_prior_pics_flag
            bit(0) // long_term_reference_flag
            se(0) // slice_qp_delta
            ue(1) // disable_deblocking_filter_idc: off
            val chromaWidth = width / 2
            for (my in 0 until mbH) {
                for (mx in 0 until mbW) {
                    ue(MB_TYPE_I_PCM)
                    alignZero() // pcm_alignment_zero_bit
                    for (r in 0 until 16) bytes(y, (my * 16 + r) * width + mx * 16, 16)
                    for (r in 0 until 8) bytes(cb, (my * 8 + r) * chromaWidth + mx * 8, 8)
                    for (r in 0 until 8) bytes(cr, (my * 8 + r) * chromaWidth + mx * 8, 8)
                }
            }
            trailing()
        }
        return Stream(sps.toArray(), pps.toArray(), slice.toArray())
    }

    /** Enough for the header, every macroblock (type, alignment and 384 samples) and a little escaping. */
    fun sliceCapacity(width: Int, height: Int): Int {
        val macroblocks = (width / 16) * (height / 16)
        return 64 + macroblocks * (384 + 3) + macroblocks
    }

    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_IDR = 5
    private const val LEVEL_5_1 = 51
    private const val MB_TYPE_I_PCM = 25
}

/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2012  I. Sokolov
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.formats.v2;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import net.pms.PMS;
import net.pms.dlna.DLNAMediaSubtitle;
import net.pms.io.OutputParams;
import net.pms.util.StringUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mozilla.universalchardet.Constants.*;

public class SubtitleUtils {
	private static final Logger LOGGER = LoggerFactory.getLogger(StringUtil.class);
	public static final String ASS_FORMAT = "%01d:%02d:%02.2f";
	public static final String SRT_FORMAT = "%02d:%02d:%02.3f";
	public static final String SEC_FORMAT = "%02d:%02d:%02d";
	private static final String TEMP_DIR = "temp";
	private final static Map<String, String> fileCharsetToMencoderSubcpOptionMap = new HashMap<String, String>() {
		private static final long serialVersionUID = 1L;

		{
			// Cyrillic / Russian
			put(CHARSET_IBM855, "enca:ru:cp1251");
			put(CHARSET_ISO_8859_5, "enca:ru:cp1251");
			put(CHARSET_KOI8_R, "enca:ru:cp1251");
			put(CHARSET_MACCYRILLIC, "enca:ru:cp1251");
			put(CHARSET_WINDOWS_1251, "enca:ru:cp1251");
			put(CHARSET_IBM866, "enca:ru:cp1251");
			// Greek
			put(CHARSET_WINDOWS_1253, "cp1253");
			put(CHARSET_ISO_8859_7, "ISO-8859-7");
			// Western Europe
			put(CHARSET_WINDOWS_1252, "cp1252");
			// Hebrew
			put(CHARSET_WINDOWS_1255, "cp1255");
			put(CHARSET_ISO_8859_8, "ISO-8859-8");
			// Chinese
			put(CHARSET_ISO_2022_CN, "ISO-2022-CN");
			put(CHARSET_BIG5, "enca:zh:big5");
			put(CHARSET_GB18030, "enca:zh:big5");
			put(CHARSET_EUC_TW, "enca:zh:big5");
			put(CHARSET_HZ_GB_2312, "enca:zh:big5");
			// Korean
			put(CHARSET_ISO_2022_KR, "cp949");
			put(CHARSET_EUC_KR, "euc-kr");
			// Japanese
			put(CHARSET_ISO_2022_JP, "ISO-2022-JP");
			put(CHARSET_EUC_JP, "euc-jp");
			put(CHARSET_SHIFT_JIS, "shift-jis");
		}
	};

	/**
	 * Returns value for -subcp option for non UTF-8 external subtitles based on
	 * detected charset.
	 * @param dlnaMediaSubtitle DLNAMediaSubtitle with external subtitles file.
	 * @return value for mencoder's -subcp option or null if can't determine.
	 */
	public static String getSubCpOptionForMencoder(DLNAMediaSubtitle dlnaMediaSubtitle) {
		if (dlnaMediaSubtitle == null) {
			throw new NullPointerException("dlnaMediaSubtitle can't be null.");
		}
		if (isBlank(dlnaMediaSubtitle.getExternalFileCharacterSet())) {
			return null;
		}
		return fileCharsetToMencoderSubcpOptionMap.get(dlnaMediaSubtitle.getExternalFileCharacterSet());
	}

	/**
	 * Applies timeseeking to subtitles file in SSA/ASS format
	 * @param SrtFile Subtitles file in SSA/ASS format
	 * @param timeseek  Time stamp value
	 * @return Converted subtitles file
	 * @throws IOException
	 */
	public static File applyTimeSeekingToASS(File SrtFile, double timeseek) throws IOException {
		Double startTime;
		Double endTime;
		String line;
		File outputSubs = new File(tempFile(SrtFile.getName() + System.currentTimeMillis()));
		BufferedWriter output;
		try (BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(SrtFile)))) {
			output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputSubs)));
			while ((line = input.readLine()) != null) {
				if (line.startsWith("Dialogue:")) {
					String[] tempStr = line.split(",");
					startTime = convertStringToTime(tempStr[1]);
					endTime = convertStringToTime(tempStr[2]);;

					if (startTime >= timeseek) {
						tempStr[1] = convertTimeToString(startTime - timeseek, ASS_FORMAT);
						tempStr[2] = convertTimeToString(endTime - timeseek, ASS_FORMAT);
					} else {
						continue;
					}

					output.write(StringUtils.join(tempStr, ",") + "\n");
				} else {
					output.write(line + "\n");
				}
			}
		}
		output.flush();
		output.close();
		PMS.get().addTempFile(outputSubs, 2 * 24 * 3600 * 1000);
		return outputSubs;
	}

	public static String tempFile(String name) {
		String dir = PMS.getConfiguration().getDataFile(TEMP_DIR); 
		File path = new File(dir);
		if (!path.exists()) {
			path.mkdirs();
		}
		return path.getAbsolutePath() + File.separator + name + ".tmp";
	}

	public static File applyTimeSeekingToSrt(File in, OutputParams params) throws IOException {
		BufferedReader reader = null;
		String cp = PMS.getConfiguration().getSubtitlesCodepage();
		if (isNotBlank(cp) && !params.sid.isExternalFileUtf8()) {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(in),cp)); // Always convert codepage
		} else if (params.timeseek > 0) {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(in))); // Apply timeseeking without codepage conversion
		} else {
			return in; // Codepage conversion or timeseeking is not needed
		}

		File out = new File(tempFile(in.getName() + System.currentTimeMillis()));
		BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out)));
		String line;
		int n = 1;

		while ((line = reader.readLine()) != null) {
			if (line .contains("-->")) {
				String startTime = line.substring(0, line.indexOf("-->") - 1);
				String endTime = line.substring(line.indexOf("-->") + 4);
				Double start = convertStringToTime(startTime);
				Double stop = convertStringToTime(endTime);

				if (start >= params.timeseek) {
					w.write("" + (n++) + "\n");
					w.write(convertTimeToString(start - params.timeseek, SRT_FORMAT));
					w.write(" --> ");	
					w.write(convertTimeToString(stop - params.timeseek, SRT_FORMAT) + "\n");

					while (isNotBlank(line = reader.readLine())) { // Read all following subs lines
						w.write(line + "\n");
					}

					w.write("" + "\n");

				}
			}
		}

		reader.close();
		w.flush();
		w.close();
		PMS.get().addTempFile(out, 2 * 24 * 3600 * 1000);
		return out;
	}
	
	/**
	 * Converts time to string.
	 *
	 * @param d time in double.
	 * @param format Format string e.g. "%02d:%02d:%02d" or use predefined constants
	 * ASS_FORMAT, SRT_FORMAT, SEC_FORMAT.
	 *
	 * @return Converted String.
	 */
	public static String convertTimeToString(double d, String format) {
		double s = d % 60;
		int h = (int) (d / 3600);
		int m = ((int) (d / 60)) % 60;

		if (format.equals(SRT_FORMAT)) {
			return String.format(format, h, m, s).replaceAll("\\.", ",");
		}

		return String.format(format, h, m, s);
	}

	/**
	 * Converts string in time format to double.
	 *
	 * @param time in string format 00:00:00.000
	 * @return Time in double.
	 */
	public static Double convertStringToTime(String time) {
		if (time == null) {
			return null;
		}

		if (time.contains(",")) {
			time = time.replaceAll(",", ".");
		}

		StringTokenizer st = new StringTokenizer(time, ":");

		try {
			int h = Integer.parseInt(st.nextToken());
			int m = Integer.parseInt(st.nextToken());
			double s = Double.parseDouble(st.nextToken());
			return h * 3600 + m * 60 + s;
		} catch (NumberFormatException nfe) {
			LOGGER.debug("Failed to convert \"" + time + "\"");
		}

		return null;
	}
}

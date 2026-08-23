/**
 * Amua - An open source modeling framework.
 * Copyright (C) 2017-2024 Zachary J. Ward
 *
 * This file is part of Amua. Amua is free software: you can redistribute
 * it and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Amua is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Amua.  If not, see <http://www.gnu.org/licenses/>.
 */


package main;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lang.Language;

/**
 * Locale-tolerant CSV reading.
 * <br><br>
 * Spreadsheets in locales that use a decimal comma write CSV files with a semicolon (or tab)
 * field delimiter and comma decimals, optionally with thousands grouping (1.000,5 or 1 000,5).
 * Amua expressions always use a decimal point, and reserve the comma as a matrix/argument
 * separator, so such files have to be translated as they are read.
 * <br><br>
 * The field delimiter and the number convention are each decided <b>once per file</b> from the
 * file's own contents, never field-by-field, so that a single file is never read with two
 * different conventions.  Fields that are not entirely a number (expressions such as
 * Normal(0,1) or [1,2,3]) are always passed through untouched.
 */
public final class CSVUtils{

	/** Field delimiters considered by {@link #detectDelimiter(String)} */
	private static final char CANDIDATES[]=new char[]{',',';','\t','|'};

	/** Max number of data rows scanned when deciding the number convention */
	private static final int SCAN_LIMIT=1000;

	/** Byte order mark */
	private static final String BOM=String.valueOf((char)0xFEFF);

	/** Thousands grouping separators: period, plain space, no-break space, narrow no-break space, thin space, figure space */
	private static final char GROUP_CHARS[]=new char[]{'.',' ',(char)0x00A0,(char)0x202F,(char)0x2009,(char)0x2007};
	private static final String GRP="[.\\u00A0\\u202F\\u2009\\u2007 ]";
	private static final String EXP="([eE][+-]?\\d+)?";

	/** Leading digits of a grouped number.  A grouped integer part never starts with a zero,
	 * which is what keeps 0.035 and 0,035 out of the grouping patterns below. */
	private static final String LEAD="[1-9]\\d{0,2}";

	/** e.g. 0,5 or -1234,56 or 1,5E-03 */
	private static final Pattern NUM_COMMA_DEC=Pattern.compile("^[+-]?\\d+,\\d+"+EXP+"$");
	/** e.g. 1.000 or 1.234.567 or 1.000,5 or 1 000,5 */
	private static final Pattern NUM_GROUPED=Pattern.compile("^[+-]?"+LEAD+"("+GRP+"\\d{3})+(,\\d+)?"+EXP+"$");
	/** e.g. 0.5 or 3.14159 - ambiguous when the fraction is exactly 3 digits (1.000) */
	private static final Pattern NUM_DOT_DEC=Pattern.compile("^[+-]?\\d+\\.(\\d+)"+EXP+"$");
	/** e.g. 1,234,567 or 1,234.56 - US style grouping */
	private static final Pattern NUM_US_GROUPED=Pattern.compile("^[+-]?"+LEAD+"(,\\d{3})+(\\.\\d+)?"+EXP+"$");
	/** a comma between digits, inside something that is not a plain number */
	private static final Pattern EMBEDDED_COMMA=Pattern.compile("\\d,\\d");

	private CSVUtils(){} //static only

	/**
	 * The conventions used by one CSV file, plus a tally of what was translated while reading it.
	 * Obtained from {@link CSVUtils#detectFormat} and then passed to {@link CSVUtils#splitLine}
	 * for every row of that file.
	 */
	public static class CSVFormat{
		/** Field delimiter used by the file */
		public char delimiter=',';
		/** true if the header row holds no delimiter at all, i.e. the file has a single column.
		 * Such rows are never split, so that a lone 1,5 or 1,234 is not torn into two fields. */
		public boolean singleColumn=false;
		/** true if the file writes numbers as 1.000,5 rather than 1,000.5 */
		public boolean commaDecimal=false;
		/** true if commaDecimal was established from the file's contents, false if it was assumed from the delimiter */
		public boolean decimalFromEvidence=false;

		/** Number of fields rewritten to Amua number format */
		public int numConverted=0;
		/** Number of rewritten fields that were ambiguous (1.000 read as one thousand) */
		public int numAmbiguous=0;
		public String ambiguousExample=null;
		/** Number of fields left untouched that contain a comma between digits (expressions) */
		public int numReview=0;
		public String reviewExample=null;

		public String getDelimiterName(Language language){
			if(delimiter=='\t'){return(language.message.getString("info.csv_delim_tab"));} //Tab
			return(String.valueOf(delimiter));
		}

		/**
		 * True if anything worth telling the user about happened.  A plain comma-delimited file
		 * with no conversions returns false, so nothing new is shown for files that already worked.
		 */
		public boolean hasSummary(){
			return((delimiter!=',' && !singleColumn) || numConverted>0 || numAmbiguous>0 || numReview>0);
		}

		/**
		 * Human-readable account of what was detected and what was changed
		 */
		public String describe(Language language){
			String decimal=commaDecimal?",":".";
			StringBuilder str=new StringBuilder();
			//Field delimiter: {0}   Decimal separator: {1}
			str.append(MessageFormat.format(language.message.getString("info.csv_format_detected"), getDelimiterName(language), decimal));
			if(numConverted>0){
				//{0} numeric value(s) converted to a decimal point
				str.append("\n").append(MessageFormat.format(language.message.getString("info.csv_values_converted"), numConverted));
			}
			if(numAmbiguous>0){
				//{0} value(s) such as {1} were read as thousands separators
				str.append("\n").append(MessageFormat.format(language.message.getString("info.csv_ambiguous_grouping"), numAmbiguous, ambiguousExample));
			}
			if(numReview>0){
				//{0} field(s) such as {1} contain a comma and were left unchanged
				str.append("\n").append(MessageFormat.format(language.message.getString("info.csv_review_fields"), numReview, reviewExample));
			}
			return(str.toString());
		}
	}


	//******************** Reading ********************

	/**
	 * Reads all lines of a text file, tolerating the encodings spreadsheets produce.
	 * UTF-8 (with or without a byte order mark) and UTF-16 are read as such; anything that is not
	 * valid UTF-8 falls back to ISO-8859-1 so that files written in a legacy Windows code page
	 * still read as they did before.  Blank lines at the end of the file are dropped.
	 */
	public static ArrayList<String> readLines(String path) throws IOException{
		byte bytes[]=Files.readAllBytes(Paths.get(path));
		String text=stripBOM(decode(bytes));
		ArrayList<String> lines=new ArrayList<String>();
		String split[]=text.split("\r\n|\r|\n",-1);
		for(int i=0; i<split.length; i++){lines.add(split[i]);}
		while(!lines.isEmpty() && lines.get(lines.size()-1).trim().isEmpty()){
			lines.remove(lines.size()-1); //drop trailing blank lines
		}
		return(lines);
	}

	/**
	 * Opens a reader for files too large to hold in memory (PSA results).  The encoding is
	 * chosen the same way as {@link #readLines(String)}, from the head of the file, and any
	 * undecodable bytes later in the file are replaced rather than throwing.
	 * The caller is responsible for closing the reader, and for passing its first line
	 * through {@link #stripBOM(String)}.
	 */
	public static BufferedReader openReader(String path) throws IOException{
		Charset charset=sniffCharset(path);
		return(new BufferedReader(new InputStreamReader(new FileInputStream(path), charset)));
	}

	public static String stripBOM(String line){
		if(line!=null && line.startsWith(BOM)){return(line.substring(1));}
		return(line);
	}

	private static String decode(byte bytes[]){
		Charset utf16=getUTF16BOM(bytes);
		if(utf16!=null){return(new String(bytes,utf16));} //the BOM decodes to U+FEFF and is stripped by the caller
		try{
			return(strictUTF8().decode(ByteBuffer.wrap(bytes)).toString());
		}catch(CharacterCodingException e){
			return(new String(bytes,StandardCharsets.ISO_8859_1)); //not UTF-8, assume legacy code page
		}
	}

	private static CharsetDecoder strictUTF8(){
		CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder();
		decoder.onMalformedInput(CodingErrorAction.REPORT);
		decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
		return(decoder);
	}

	private static Charset sniffCharset(String path) throws IOException{
		byte head[]=new byte[8192];
		int len=0;
		FileInputStream in=new FileInputStream(path);
		try{
			int read=in.read(head);
			if(read>0){len=read;}
		}finally{
			in.close();
		}
		byte bytes[]=new byte[len];
		System.arraycopy(head,0,bytes,0,len);
		Charset utf16=getUTF16BOM(bytes);
		if(utf16!=null){return(utf16);}
		try{
			strictUTF8().decode(ByteBuffer.wrap(bytes));
			return(StandardCharsets.UTF_8);
		}catch(CharacterCodingException e){
			//the head may simply have been cut mid-character
			if(len>=head.length){return(StandardCharsets.UTF_8);}
			return(StandardCharsets.ISO_8859_1);
		}
	}

	private static Charset getUTF16BOM(byte bytes[]){
		if(bytes.length>=2){
			if((bytes[0]&0xFF)==0xFF && (bytes[1]&0xFF)==0xFE){return(Charset.forName("UTF-16LE"));}
			if((bytes[0]&0xFF)==0xFE && (bytes[1]&0xFF)==0xFF){return(Charset.forName("UTF-16BE"));}
		}
		return(null);
	}


	//******************** Detection ********************

	/**
	 * Decides the field delimiter and the number convention for a whole file.
	 * @param lines All lines of the file, header first (see {@link #readLines(String)})
	 * @throws Exception if the file mixes decimal commas and decimal points, which cannot be
	 * resolved without guessing at individual values
	 */
	public static CSVFormat detectFormat(ArrayList<String> lines, Language language) throws Exception{
		CSVFormat fmt=new CSVFormat();
		if(lines==null || lines.isEmpty()){return(fmt);}
		fmt.delimiter=detectDelimiter(lines.get(0));
		fmt.singleColumn=!hasAnyDelimiter(lines.get(0));

		//A comma-delimited file cannot contain decimal commas, so it is read exactly as before
		if(fmt.delimiter==',' && !fmt.singleColumn){
			fmt.decimalFromEvidence=true;
			return(fmt);
		}

		String commaEvidence=null, dotEvidence=null;
		int numScanned=0;
		for(int i=1; i<lines.size() && numScanned<SCAN_LIMIT; i++){
			String line=lines.get(i);
			if(line.trim().isEmpty()){continue;}
			numScanned++;
			String data[]=splitRaw(line,fmt);
			for(int c=0; c<data.length; c++){
				String core=stripQuotes(data[c]);
				if(commaEvidence==null && isCommaDecimalEvidence(core)){commaEvidence=core;}
				if(dotEvidence==null && isDotDecimalEvidence(core)){dotEvidence=core;}
			}
			if(commaEvidence!=null && dotEvidence!=null){break;}
		}

		if(commaEvidence!=null && dotEvidence!=null){
			//Error: mixed decimal separators. {0} uses a decimal comma, {1} uses a decimal point.
			throw new Exception(MessageFormat.format(language.message.getString("err.csv_mixed_decimals"), commaEvidence, dotEvidence));
		}
		if(commaEvidence!=null){
			fmt.commaDecimal=true; fmt.decimalFromEvidence=true;
		}
		else if(dotEvidence!=null){
			fmt.commaDecimal=false; fmt.decimalFromEvidence=true;
		}
		else{ //no unambiguous value anywhere in the file; a non-comma delimiter implies a decimal comma locale
			fmt.commaDecimal=!fmt.singleColumn; fmt.decimalFromEvidence=false;
		}
		return(fmt);
	}

	/**
	 * Same as {@link #detectFormat(ArrayList, Language)}, but reads only the head of the file.
	 * For files that are streamed rather than held in memory.
	 */
	public static CSVFormat sniffFormat(String path, Language language) throws Exception{
		ArrayList<String> head=new ArrayList<String>();
		BufferedReader br=openReader(path);
		try{
			String strLine=stripBOM(br.readLine());
			while(strLine!=null && head.size()<=SCAN_LIMIT){
				head.add(strLine);
				strLine=br.readLine();
			}
		}finally{
			br.close();
		}
		return(detectFormat(head,language));
	}

	/**
	 * Counts each candidate delimiter outside of quoted regions of the header row.  The most
	 * frequent wins; a comma wins ties, and is used for a single-column file.
	 */
	public static char detectDelimiter(String headerLine){
		if(headerLine==null){return(',');}
		String line=stripBOM(headerLine);
		char best=',';
		int bestCount=0;
		for(int i=0; i<CANDIDATES.length; i++){
			int count=countOutsideQuotes(line,CANDIDATES[i]);
			if(count>bestCount){bestCount=count; best=CANDIDATES[i];}
		}
		return(best);
	}

	/** False if the header row holds none of the candidate delimiters, i.e. a single column file */
	private static boolean hasAnyDelimiter(String headerLine){
		if(headerLine==null){return(false);}
		String line=stripBOM(headerLine);
		for(int i=0; i<CANDIDATES.length; i++){
			if(countOutsideQuotes(line,CANDIDATES[i])>0){return(true);}
		}
		return(false);
	}

	private static int countOutsideQuotes(String line, char test){
		int count=0;
		boolean inQuote=false;
		for(int i=0; i<line.length(); i++){
			char c=line.charAt(i);
			if(c=='"'){inQuote=!inQuote;}
			else if(c==test && !inQuote){count++;}
		}
		return(count);
	}

	/** 1.000,5 / 0,5 / 1.234.567 / 1[nbsp]000,5 - only a decimal comma locale writes these */
	private static boolean isCommaDecimalEvidence(String core){
		if(NUM_COMMA_DEC.matcher(core).matches()){
			//1,500 is also valid US grouping: ambiguous, not evidence
			return(!NUM_US_GROUPED.matcher(core).matches());
		}
		if(NUM_GROUPED.matcher(core).matches()){
			if(core.indexOf(',')!=-1){return(true);} //1.000,5
			if(countGroupChars(core)>1){return(true);} //1.234.567
			if(countGroupChars(core)==1 && core.indexOf('.')==-1){return(true);} //1[space]000
		}
		return(false);
	}

	/** 0.5 / 3.14159 / 1,234,567 / 1,234.56 - only a decimal point locale writes these */
	private static boolean isDotDecimalEvidence(String core){
		Matcher dot=NUM_DOT_DEC.matcher(core);
		if(dot.matches()){
			//exactly 3 fraction digits is ambiguous with grouping (1.000), unless the number
			//could not have been grouped in the first place (0.035)
			if(dot.group(1).length()!=3 || !NUM_GROUPED.matcher(core).matches()){return(true);}
		}
		if(NUM_US_GROUPED.matcher(core).matches()){
			if(core.indexOf('.')!=-1){return(true);} //1,234.56
			if(countChar(core,',')>1){return(true);} //1,234,567
		}
		return(false);
	}

	private static boolean isGroupChar(char c){
		for(int i=0; i<GROUP_CHARS.length; i++){
			if(c==GROUP_CHARS[i]){return(true);}
		}
		return(false);
	}

	private static int countGroupChars(String core){
		int count=0;
		for(int i=0; i<core.length(); i++){
			if(isGroupChar(core.charAt(i))){count++;}
		}
		return(count);
	}

	private static int countChar(String core, char test){
		int count=0;
		for(int i=0; i<core.length(); i++){
			if(core.charAt(i)==test){count++;}
		}
		return(count);
	}


	//******************** Splitting ********************

	/**
	 * Splits one data row and converts any number written in the file's convention into
	 * Amua number format.  Fields that are not entirely a number are returned untouched.
	 */
	public static String[] splitLine(String line, CSVFormat fmt){
		String data[]=splitRaw(line,fmt);
		if(fmt.commaDecimal){
			for(int c=0; c<data.length; c++){
				data[c]=normalizeNumber(data[c],fmt);
			}
		}
		return(data);
	}

	/**
	 * Splits a header row.  Column names are never treated as numbers.
	 */
	public static String[] splitHeader(String line, CSVFormat fmt){
		return(splitRaw(stripBOM(line),fmt));
	}

	/**
	 * Splits on the file's delimiter, or returns the whole row when the file has one column.
	 */
	private static String[] splitRaw(String line, CSVFormat fmt){
		if(fmt.singleColumn){return(new String[]{line});}
		return(rawSplit(line,fmt.delimiter));
	}

	/**
	 * Splits on the delimiter, ignoring delimiters inside quoted fields.
	 */
	public static String[] rawSplit(String line, char delimiter){
		String regex=Pattern.quote(String.valueOf(delimiter))+"(?=([^\"]*\"[^\"]*\")*[^\"]*$)";
		return(line.split(regex));
	}

	/**
	 * Rewrites a single field from a decimal comma file into Amua number format, if and only if
	 * the field is entirely a number.  Expressions such as Normal(0,1) or [1,2,3] are left alone:
	 * the comma is Amua's own argument/matrix separator and cannot be rewritten here.
	 * Surrounding quotes and whitespace are preserved.
	 */
	public static String normalizeNumber(String field, CSVFormat fmt){
		if(field==null || field.isEmpty()){return(field);}
		if(!fmt.commaDecimal){return(field);}

		//peel off quotes/whitespace so they can be put back
		int start=0, end=field.length();
		while(start<end && (field.charAt(start)=='"' || field.charAt(start)==' ' || field.charAt(start)=='\t')){start++;}
		while(end>start && (field.charAt(end-1)=='"' || field.charAt(end-1)==' ' || field.charAt(end-1)=='\t')){end--;}
		String prefix=field.substring(0,start);
		String core=field.substring(start,end);
		String suffix=field.substring(end);
		if(core.isEmpty()){return(field);}

		boolean commaDec=NUM_COMMA_DEC.matcher(core).matches();
		boolean grouped=NUM_GROUPED.matcher(core).matches();
		if(!commaDec && !grouped){
			//not a number: flag expressions that carry a comma between digits, but never touch them
			if(EMBEDDED_COMMA.matcher(core).find()){
				fmt.numReview++;
				if(fmt.reviewExample==null){fmt.reviewExample=core;}
			}
			return(field);
		}

		boolean ambiguous=false;
		if(grouped && core.indexOf(',')==-1 && core.indexOf('.')!=-1){
			ambiguous=true; //1.000 read as grouping, which is what the rest of the file implies
		}
		else if(commaDec && NUM_US_GROUPED.matcher(core).matches()){
			ambiguous=true; //1,500 read as 1.5 under this file's convention
		}

		StringBuilder converted=new StringBuilder();
		for(int i=0; i<core.length(); i++){
			char c=core.charAt(i);
			if(c==','){converted.append('.');}
			else if(isGroupChar(c)){/*thousands grouping, drop*/}
			else{converted.append(c);}
		}

		fmt.numConverted++;
		if(ambiguous){
			fmt.numAmbiguous++;
			if(fmt.ambiguousExample==null){fmt.ambiguousExample=core;}
		}
		return(prefix+converted.toString()+suffix);
	}

	private static String stripQuotes(String field){
		return(field.replaceAll("\"","").trim());
	}

}

/*******************************************************************************
 * Copyright (c) 2013 hangum.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     hangum - initial API and implementation
 ******************************************************************************/
package com.hangum.tadpole.engine.manager;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import com.hangum.tadpole.commons.exception.TadpoleSQLManagerException;
import com.hangum.tadpole.commons.libs.core.define.PublicTadpoleDefine;
import com.hangum.tadpole.db.metadata.TadpoleMetaData;
import com.hangum.tadpole.db.metadata.constants.SQLConstants;
import com.hangum.tadpole.engine.define.DBDefine;
import com.hangum.tadpole.engine.manager.internal.map.SQLMap;
import com.hangum.tadpole.engine.query.dao.system.UserDBDAO;
import com.hangum.tadpole.session.manager.SessionManager;
import com.ibatis.sqlmap.client.SqlMapClient;

/**
 * DB Manager에서 관리하는 디비에 관해서 정의한다.
 * 
 * @author hangum
 *
 */
public class TadpoleSQLManager extends AbstractTadpoleManager {
	private static final Logger logger = Logger.getLogger(TadpoleSQLManager.class);
	
	/** db 인스턴스를 가지고 있는 아이 */
	private static Map<String, SqlMapClient> dbManager = null;
	private final static Map<String, TadpoleMetaData> dbMetadata = new HashMap<String, TadpoleMetaData>();
	/** dbManager 의 키를 가지고 있는 친구 - logout시에 키를 사용하여 인스턴스를 삭제하기 위해 */
	private final static Map<String, List<String>> managerKey = new HashMap<String, List<String>>();
	
	private static TadpoleSQLManager tadpoleSQLManager = null;
	
	static {
		if(tadpoleSQLManager == null) {
			tadpoleSQLManager = new TadpoleSQLManager();
			dbManager = new HashMap<String, SqlMapClient>();
		} 
	}
	
	private TadpoleSQLManager() {}
	
	/**
	 * <pre>
	 * DB 정보를 생성한다.
	 * 
	 * 엔진환경에서 가지고 있어야 하는 것으로서 데이터베이스 부가정보를 가지고 있어야 할듯합니다.
	 * 	테이블의 "나 '가 대소문자 유무등을 환경정보로가지고 있어야겟습니다.
	 * 
	 * </pre>
	 * 
	 * @param userDB
	 * @return
	 * @throws Exception
	 */
	public static SqlMapClient getInstance(final UserDBDAO userDB) throws TadpoleSQLManagerException {
		SqlMapClient sqlMapClient = null;
		Connection conn = null;
		
//		synchronized (dbManager) {
		String searchKey = getKey(userDB);
		try {
			sqlMapClient = dbManager.get( searchKey );
			if(sqlMapClient == null) {

				// oracle 일 경우 설정 
				try { 
					if(userDB.getDBDefine() == DBDefine.ORACLE_DEFAULT ||
							userDB.getDBDefine() == DBDefine.TIBERO_DEFAULT) {
						DriverManager.setLoginTimeout(10);
						if(userDB.getLocale() != null && !"".equals(userDB.getLocale())) {
							Locale.setDefault(new Locale(userDB.getLocale()));
						}
					}
				} catch(Exception e) {
					logger.error("set locale error", e);
				}
				
				// connection pool 을 가져옵니다.
				sqlMapClient = SQLMap.getInstance(userDB);
				dbManager.put(searchKey, sqlMapClient);
				List<String> listSearchKey = managerKey.get(userDB.getTdbUserID());
				if(listSearchKey == null) {
					listSearchKey = new ArrayList<String>();
					listSearchKey.add(searchKey);
					
					managerKey.put(userDB.getTdbUserID(), listSearchKey);
				} else {
					listSearchKey.add(searchKey);
				}
				
				// metadata를 가져와서 저장해 놓습니다.
				conn = sqlMapClient.getDataSource().getConnection();
				
				// connection initialize
				setConnectionInitialize(userDB, conn);
				
				// don't belive keyword. --;;
				initializeConnection(searchKey, userDB, conn.getMetaData());
			}
			
		} catch(Exception e) {
			logger.error("=== get DB Instance seq is " + userDB.getSeq() + "\n" , e);
			
			dbManager.remove(searchKey);
			
			throw new TadpoleSQLManagerException(e);
		} finally {
			if(conn != null) try {conn.close();} catch(Exception e) {}
		}

		return sqlMapClient;
	}
	
	/**
	 * 각 DB의 metadata를 넘겨줍니다.
	 * 
	 * @param searchKey
	 * @param userDB
	 * @param dbMetadata
	 * @return
	 */
	public static void initializeConnection(String searchKey, final UserDBDAO userDB, DatabaseMetaData dbMetaData) throws Exception {
		
		// 엔진디비는 메타데이터를 저장하지 않는다.
		if(userDB.getDBDefine() == DBDefine.TADPOLE_SYSTEM_DEFAULT || userDB.getDBDefine() == DBDefine.TADPOLE_SYSTEM_MYSQL_DEFAULT) return;
				
		String strIdentifierQuoteString = "";
		try {
			strIdentifierQuoteString = dbMetaData.getIdentifierQuoteString();
		} catch(Exception e) {
			// ignore exception, not support quoteString
		}
		
		// https://github.com/hangum/TadpoleForDBTools/issues/412 디비의 메타데이터가 틀려서 설정하였습니다.
		TadpoleMetaData tadpoleMetaData = null;
		switch ( userDB.getDBDefine() ) {
			case ORACLE_DEFAULT:
			case TIBERO_DEFAULT:
				tadpoleMetaData = new TadpoleMetaData(strIdentifierQuoteString, TadpoleMetaData.STORES_FIELD_TYPE.LOWCASE_BLANK);
				break;
			case MSSQL_DEFAULT:			
			case MSSQL_8_LE_DEFAULT:
			case MYSQL_DEFAULT:
			case MARIADB_DEFAULT:
			case SQLite_DEFAULT:		
				tadpoleMetaData = new TadpoleMetaData(strIdentifierQuoteString, TadpoleMetaData.STORES_FIELD_TYPE.BLANK);
				break;
			case POSTGRE_DEFAULT:		
			case TAJO_DEFAULT: 			
				tadpoleMetaData = new TadpoleMetaData(strIdentifierQuoteString, TadpoleMetaData.STORES_FIELD_TYPE.UPPERCASE_BLANK);
				break;
			default:
				tadpoleMetaData = new TadpoleMetaData(strIdentifierQuoteString, TadpoleMetaData.STORES_FIELD_TYPE.NONE);
		}
		
		// set keyword
		if(userDB.getDBDefine() == DBDefine.SQLite_DEFAULT) {
			// not support keyword http://sqlite.org/lang_keywords.html
			tadpoleMetaData.setKeywords(StringUtils.join(SQLConstants.QUOTE_SQLITE_KEYWORDS, ","));
		} else if(userDB.getDBDefine() == DBDefine.MYSQL_DEFAULT || 
					userDB.getDBDefine() == DBDefine.MYSQL_DEFAULT || 
					userDB.getDBDefine() == DBDefine.ORACLE_DEFAULT ||
					userDB.getDBDefine() == DBDefine.TIBERO_DEFAULT) {
			String strFullKeywords = StringUtils.join(SQLConstants.QUOTE_MYSQL_KEYWORDS, ",") + "," + dbMetadata;
			tadpoleMetaData.setKeywords(strFullKeywords);
		} else if(userDB.getDBDefine() == DBDefine.MONGODB_DEFAULT) {
			// not support this method
			tadpoleMetaData.setKeywords("");
		} else if(userDB.getDBDefine() == DBDefine.MSSQL_8_LE_DEFAULT ||
				userDB.getDBDefine() == DBDefine.MSSQL_DEFAULT
		) {
			String strFullKeywords = StringUtils.join(SQLConstants.QUOTE_MSSQL_KEYWORDS, ",") + "," + dbMetaData.getSQLKeywords();
			tadpoleMetaData.setKeywords(strFullKeywords);
		} else {
			tadpoleMetaData.setKeywords(dbMetaData.getSQLKeywords());
		}
						
		tadpoleMetaData.setDbMajorVersion(dbMetaData.getDatabaseMajorVersion());
		tadpoleMetaData.setMinorVersion(dbMetaData.getDatabaseMinorVersion());
		dbMetadata.put(searchKey, tadpoleMetaData);
	}
	
	/**
	 * 현재 연결된 Connection pool 정보를 리턴합니다.
	 * 
	 * @return
	 */
	public static Map<String, SqlMapClient> getDbManager() {
		return dbManager;
	}

	/**
	 * dbcp pool info
	 * 
	 * @return
	 */
	public static List<DBCPInfoDAO> getDBCPInfo() {
		List<DBCPInfoDAO> listDbcpInfo = new ArrayList<DBCPInfoDAO>();
		final String strLoginEmail = SessionManager.getEMAIL();
		
		Set<String> setKeys = getDbManager().keySet();
		for (String stKey : setKeys) {
			String strArryKey[] = StringUtils.splitByWholeSeparator(stKey, PublicTadpoleDefine.DELIMITER);
			if(!StringUtils.equals(strLoginEmail, strArryKey[0])) continue; 
			
			SqlMapClient sqlMap = dbManager.get(stKey);
			DataSource ds = sqlMap.getDataSource();
			BasicDataSource bds = (BasicDataSource)ds;
				
			DBCPInfoDAO dao = new DBCPInfoDAO();
			dao.setDbSeq(Integer.parseInt(strArryKey[1]));
			dao.setDbType(strArryKey[2]);
			dao.setDisplayName(strArryKey[3]);
			
			dao.setNumberActive(bds.getNumActive());
			dao.setMaxActive(bds.getMaxActive());
			dao.setNumberIdle(bds.getNumIdle());
			dao.setMaxWait(bds.getMaxWait());
			
			listDbcpInfo.add(dao);
		}
		
		return listDbcpInfo;
	}
	
	/**
	 * DBMetadata
	 * @return
	 */
	public static TadpoleMetaData getDbMetadata(final UserDBDAO dbInfo) {
		return dbMetadata.get(getKey(dbInfo));
	}
	
	/**
	 * 
	 */
	public static void removeAllInstance(String id) {
		List<String> listKeyMap = managerKey.get(id);
		if(listKeyMap == null) return;
		for (String searchKey : listKeyMap) {
			removeInstance(searchKey);
		}
	}
	
	/**
	 * DB 정보를 삭제한다.
	 * 
	 * @param dbInfo
	 */
	public static void removeInstance(UserDBDAO dbInfo) {
		synchronized (dbManager) {
			String key = getKey(dbInfo);
			removeInstance(key);
		}
	}
	
	/**
	 * remove instance
	 * @param searchKey
	 */
	private static void removeInstance(String searchKey) {
		if(logger.isDebugEnabled()) logger.debug("\t #### [TadpoleSQLManager] remove Instance: " + searchKey);
		synchronized (dbManager) {
			TadpoleMetaData metaData = dbMetadata.remove(searchKey);
			metaData = null;
			
			SqlMapClient sqlMapClient = null;
			try {
				sqlMapClient = dbManager.remove(searchKey);
				if(sqlMapClient == null) return;
				BasicDataSource basicDataSource = (BasicDataSource)sqlMapClient.getDataSource();
				basicDataSource.close();
			} catch (Exception e) {
				logger.error("remove connection", e);
			} finally {
				sqlMapClient = null;	
			}
		}
	}
	
	/**
	 * map의 카를 가져옵니다.
	 * @param dbInfo
	 * @return
	 */
	public static String getKey(UserDBDAO dbInfo) {
		return 	dbInfo.getTdbUserID()   + PublicTadpoleDefine.DELIMITER +
				dbInfo.getSeq()  		+ PublicTadpoleDefine.DELIMITER + 
				dbInfo.getDbms_type()  	+ PublicTadpoleDefine.DELIMITER +
				dbInfo.getDisplay_name()+ PublicTadpoleDefine.DELIMITER +
				dbInfo.getUrl()  		+ PublicTadpoleDefine.DELIMITER +
				dbInfo.getUsers()  		+ PublicTadpoleDefine.DELIMITER;
	}

}

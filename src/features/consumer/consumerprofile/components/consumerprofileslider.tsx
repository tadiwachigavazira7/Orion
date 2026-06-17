import { Image, Pressable, StyleSheet, Text, View } from 'react-native';

interface SliderProps {
  activeTab: string;
  onTabChange: (tab: string) => void;
  firstName: string;
  lastName: string;
}

export function ConsumerProfileSlider({ activeTab, onTabChange, firstName, lastName }: SliderProps) {
  const tabs = ['Edit Profile', 'Payment Methods', 'Purchases'];

  // Calculate the live dynamic initials
  const firstInitial = firstName ? firstName.charAt(0).toUpperCase() : '';
  const lastInitial = lastName ? lastName.charAt(0).toUpperCase() : '';
  const initials = (firstInitial + lastInitial) || '??';
  const logo = require('../../../../../assets/images/OrionLogo.png');

  return (
    <View style={styles.headerWrapper}>
      
      {/* Orion Logo placed perfectly in the top right corner */}
      <View style={styles.brandRow}>
        <Image source={logo} style={{height: 100, width: 100, marginTop: -25}} resizeMode="contain" />
      </View>

      {/* Avatar Box on left, Plan details on the right */}
      <View style={styles.profileMetaRow}>
        <View style={styles.avatarCircle}>
          <Text style={styles.avatarText}>{initials}</Text>
        </View>
        
        <View style={styles.planDetails}>
          <Text style={styles.planTitleText}>Plan: Consumer Basic</Text>
          <Pressable onPress={() => console.log('Change Plan Clicked')}>
            <Text style={styles.changePlanText}>Change Plan</Text>
          </Pressable>
        </View>
      </View>

      {/* Tab Switcher navigation row directly below the meta header */}
      <View style={styles.tabsContainer}>
        {tabs.map((tab) => {
          const isActive = activeTab === tab;
          return (
            <Pressable
              key={tab}
              onPress={() => onTabChange(tab)}
              style={styles.tabButton}
            >
              <Text style={[styles.tabText, isActive && styles.activeTabText]}>
                {tab}
              </Text>
              <View style={[styles.underline, isActive && styles.activeUnderline]} />
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  headerWrapper: {
    width: '100%',
    backgroundColor: 'white',
    paddingTop: 10,
  },
  brandRow: {
    width: '100%',
    flexDirection: 'row',
    justifyContent: 'flex-end',
    paddingHorizontal: 4,
    marginBottom: 5,
  },
  brandText: {
    fontSize: 20,
    fontWeight: '600',
    color: 'black',
    fontFamily: 'serif', // Gives it that clean brand look
  },
  profileMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    width: '100%',
    paddingHorizontal: 4,
    marginBottom: 20,
  },
  logoImage: {
    width: 200,
    height: 65,
  },
  avatarCircle: {
    width: 85,
    height: 85,
    borderRadius: 42.5,
    backgroundColor: '#EAEAEA',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 20,
  },
  avatarText: {
    fontSize: 28,
    fontWeight: '500',
    color: 'black',
  },
  planDetails: {
    justifyContent: 'center',
  },
  planTitleText: {
    fontSize: 19,
    fontWeight: '500',
    color: 'black',
    marginBottom: 4,
  },
  changePlanText: {
    fontSize: 13,
    fontWeight: '600',
    fontStyle: 'italic',
    textDecorationLine: 'underline',
    color: 'black',
  },
  tabsContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    width: '100%',
    borderBottomWidth: 1,
    borderBottomColor: '#CCCCCC', 
  },
  tabButton: {
    flex: 1,
    alignItems: 'center',
  },
  tabText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#666666',
    paddingBottom: 8,
  },
  activeTabText: {
    color: 'black',
    fontWeight: '700',
  },
  underline: {
    height: 2,
    width: '100%',
    backgroundColor: 'transparent',
  },
  activeUnderline: {
    backgroundColor: 'black',
  },
});